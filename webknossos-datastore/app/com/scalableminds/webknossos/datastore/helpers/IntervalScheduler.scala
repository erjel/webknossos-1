package com.scalableminds.webknossos.datastore.helpers

import akka.actor.{ActorSystem, Cancellable}
import play.api.inject.ApplicationLifecycle

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

trait IntervalScheduler {

  protected def lifecycle: ApplicationLifecycle

  protected def system: ActorSystem

  protected def enabled: Boolean = true

  protected def tickerInterval: FiniteDuration

  protected def tickerInitialDelay: FiniteDuration = 10 seconds

  protected def tick(): Unit

  private var scheduled: Cancellable = _

  lifecycle.addStopHook(stop _)

  if (enabled) {
    scheduled = system.scheduler.scheduleWithFixedDelay(tickerInitialDelay, tickerInterval)(() => tick())
  }

  private def stop(): Future[Unit] = {
    if (scheduled != null) {
      scheduled.cancel()
      scheduled = null
    }
    Future.successful(())
  }
}
