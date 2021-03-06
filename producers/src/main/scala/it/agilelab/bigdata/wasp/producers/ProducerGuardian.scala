package it.agilelab.bigdata.wasp.producers

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import akka.cluster.Cluster
import akka.pattern.gracefulStop
import akka.routing.BalancingPool
import it.agilelab.bigdata.wasp.core.WaspSystem
import it.agilelab.bigdata.wasp.core.WaspSystem.{??, actorSystem, generalTimeout}
import it.agilelab.bigdata.wasp.core.bl.{ProducerBL, TopicBL}
import it.agilelab.bigdata.wasp.core.kafka.CheckOrCreateTopic
import it.agilelab.bigdata.wasp.core.logging.Logging
import it.agilelab.bigdata.wasp.core.messages.{Start, Stop}
import it.agilelab.bigdata.wasp.core.models.{ProducerModel, TopicModel}
import it.agilelab.bigdata.wasp.core.utils.ConfigManager

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Base class for a WASP producer. A ProducerGuardian represents a producer and manages the lifecycle of the child
  * ProducerActors that actually produce the data.
  */
abstract class ProducerGuardian(env: {val producerBL: ProducerBL; val topicBL: TopicBL}, producerName: String)
  extends Actor
    with Logging {

  val name: String

  // initialized in initialize()
  var producer: ProducerModel = _
  var associatedTopic: Option[TopicModel] = _
  var router_name: String = _
  var kafka_router: ActorRef = _ // TODO: Careful with kafka router dynamic name

  val cluster = Cluster(context.system)

  override def preStart(): Unit = {
    // we start in uninitialized state
    context become uninitialized
  }

  override def postStop(): Unit = {
    kafka_router ! PoisonPill
  }

  // standard receive
  // NOTE: THIS IS IMMEDIATELY SWITCHED TO uninitialized DURING preStart(), DO NOT USE!
  override def receive: Actor.Receive = uninitialized

  def uninitialized: Actor.Receive = {
    case Start =>
      logger.info(s"Producer '$producerName' starting at guardian $self")
      val result = initialize()
      sender() ! result

    case Stop =>
      logger.info(s"Producer '$producerName' already stopped")
      sender() ! Right(())
  }

  def initialized: Actor.Receive = {
    case Start =>
      logger.info(s"Producer '$producerName' already started")
      sender() ! Right(())

    case Stop =>
      logger.info(s"Producer '$producerName' stopping")
      stopChildActors()
  }

  def startChildActors()

  def stopChildActors(): Unit = {

    //Stop all actors bound to this guardian and the guardian itself
    logger.info(s"Producer '$producerName': stopping actors bound to $self...")

    import scala.concurrent.duration._
    val timeoutDuration = generalTimeout.duration - 15.seconds
    val globalStatus = Future.traverse(context.children)(gracefulStop(_, timeoutDuration))

    val senderTmp = sender()
    globalStatus map { res =>
      if (res reduceLeft (_ && _)) {

        logger.info(s"Producer '$producerName': graceful shutdown completed.")
        env.producerBL.setIsActive(producer, isActive = false)

        logger.info(s"Producer '$producerName': transitioning from 'initialized' to 'uninitialized'")
        kafka_router ! PoisonPill

        context become uninitialized

        senderTmp ! Right(())
      } else {
        val msg = s"Producer '$producerName': something went wrong! Unable to shutdown all nodes"
        logger.error(msg)
        senderTmp ! Left(msg)
      }
    }
  }

  def initialize(): Either[String, Unit] = {

    val producerOption = env.producerBL.getByName(producerName)

    if (producerOption.isDefined) {
      producer = producerOption.get
      if (producer.hasOutput) {
        val topicOption = env.producerBL.getTopic(topicBL = env.topicBL, producer)

        associatedTopic = topicOption
        logger.info(s"Producer '$producerName': topic found: $associatedTopic")
        val result = ??[Boolean](WaspSystem.kafkaAdminActor, CheckOrCreateTopic(topicOption.get.name, topicOption.get.partitions, topicOption.get.replicas))
        if (result) {
          router_name = s"kafka-ingestion-router-$name-${System.currentTimeMillis()}"
          kafka_router = actorSystem.actorOf(BalancingPool(5).props(Props(new KafkaPublisherActor(ConfigManager.getKafkaConfig))), router_name)
          context become initialized
          startChildActors()

          env.producerBL.setIsActive(producer, isActive = true)

          Right(())
        } else {
          val msg = s"Producer '$producerName': error creating topic " + topicOption.get.name
          logger.error(msg)
          Left(msg)
        }
      } else {
        val msg = s"Producer '$producerName': error undefined topic"
        logger.error(msg)
        Left(msg)
      }
    } else {
      val msg = s"Producer '$producerName': error not defined"
      logger.error(msg)
      Left(msg)
    }
  }
}