package com.evecentral.api

import akka.actor.{Props, Actor}
import akka.dispatch.Future
import akka.pattern.ask
import akka.util.Timeout
import akka.util.duration._
import com.codahale.jerkson.Json._
import com.evecentral.ParameterHelper._
import com.evecentral._
import com.evecentral.dataaccess._
import com.evecentral.frontend.Formatter.priceString
import dataaccess.OrderList
import datainput.{OldUploadParsingActor, OldUploadPayload}
import frontend.DateFormats
import net.liftweb.json._
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import routes.{Jump, RouteBetween}
import scala.xml._
import spray.http.HttpHeaders.RawHeader
import spray.http.StatusCodes
import spray.httpx.LiftJsonSupport
import spray.routing.{HttpService, RequestContext}
import util.{ActorNames, BaseOrderQuery}


case class QuickLookSimpleQuery(ctx: RequestContext)
case class QuickLookPathQuery(ctx: RequestContext, from: SolarSystem, to: SolarSystem, types: Int)

class QuickLookQuery extends Actor with FixedSprayMarshallers with BaseOrderQuery {

	import com.evecentral.ParameterHelper._
	import context.dispatcher

	override implicit val timeout : Timeout = 60.seconds

	def receive = {
		case QuickLookPathQuery(ctx, froms, tos, types) =>

			val params = listFromContext(ctx)

			val setHours = singleParam("sethours", params) match {
				case Some(x) => x
				case None => 360
			}
			val minq = singleParam("setminQ", params)
			ctx.complete(queryQuicklookPath(types, setHours, minq, froms, tos))

		case QuickLookSimpleQuery(ctx) =>

			val params = listFromContext(ctx)

			val typeid = singleParam("typeid", params) match {
				case Some(x) => x
				case None => 34
			}
			val setHours = singleParam("sethours", params) match {
				case Some(x) => x
				case None => 360
			}
			val regionLimit = paramsFromQuery("regionlimit", params).map(_.toLong).distinct
			val usesystem = singleParam("usesystem", params)
			val minq = singleParam("setminQ", params)
			val result = queryQuicklook(typeid, setHours, regionLimit, usesystem, minq)
			result.onComplete {
				case Left(t) => ctx.failWith(t)
				case Right(r) => ctx.complete(r)
			}
	}

	def regionName(regions: List[Long]): NodeSeq = {
		regions.foldLeft(Seq[Node]()) {
			(i, regionid) =>
				i ++ <region>{StaticProvider.regionsMap(regionid).name}</region>
		}
	}

	def showOrders(orders: Option[OrderList]): NodeSeq = {
		orders match {
			case None => Seq[Node]()
			case Some(o) => o.result.foldLeft(Seq[Node]()) {
				(i, order) =>
					i ++ <order id={order.orderId.toString}>
						<region>{order.region.regionid}</region>
						<station>{order.station.stationid}</station>
						<station_name>{order.station.name}</station_name>
						<security>{order.system.security}</security>
						<range>{order.range}</range>
						<price>{priceString(order.price)}</price>
						<vol_remain>{order.volremain}</vol_remain>
						<min_volume>{order.minVolume}</min_volume>
						<expires>{DateFormats.dateOnly.print(new DateTime().plus(order.expires))}</expires>
						<reported_time>{DateFormats.dateTime.print(order.reportedAt)}</reported_time>
					</order>
			}
		}
	}

	def queryQuicklookPath(typeid: Long, setHours: Long, qminq: Option[Long], froms: SolarSystem, tos: SolarSystem) : Future[NodeSeq] = {

		val minq = qminq match {
			case Some(x) => x
			case None => QueryDefaults.minQ(typeid)
		}

		val path = (pathActor ? RouteBetween(froms, tos)).mapTo[Seq[Jump]]
		val systems = path.map { jumps => jumps.foldLeft(Set[SolarSystem]()) { (set, j) => set + j.from + j.to }.toList.map(_.systemid) }
		systems.flatMap { systems =>
			val buyq = GetOrdersFor(Some(true), List(typeid), List(), systems, setHours)
			val selq = GetOrdersFor(Some(false), List(typeid), List(), systems, setHours)
			Future.sequence(Seq(ordersActor ? buyq, ordersActor ? selq))
		}.map { l =>
			val buyr = l(0).asInstanceOf[OrderList]
			val selr = l(1).asInstanceOf[OrderList]
			<evec_api version="2.0" method="quicklook_path">
				<quicklook>
					<item>{typeid}</item>
					<itemname>{StaticProvider.typesMap(typeid).name}</itemname>
					<regions></regions>
					<hours>{setHours}</hours>
					<minqty>{minq}</minqty>
					<sell_orders>{showOrders(Some(selr))}</sell_orders>
					<buy_orders>{showOrders(Some(buyr))}</buy_orders>
					<from>{froms.systemid}</from>
					<to>{tos.systemid}</to>
				</quicklook>
			</evec_api>
		}
	}

	def queryQuicklook(typeid: Long, setHours: Long, regionLimit: List[Long],
	                   usesystem: Option[Long], qminq: Option[Long]): Future[NodeSeq] = {

		val minq = qminq match {
			case Some(x) => x
			case None => QueryDefaults.minQ(typeid)
		}

		val buyq = GetOrdersFor(Some(true), List(typeid), regionLimit, usesystem match {
			case None => Nil
			case Some(x) => List[Long](x)
		}, setHours)
		val selq = GetOrdersFor(Some(false), List(typeid), regionLimit, usesystem match {
			case None => Nil
			case Some(x) => List[Long](x)
		}, setHours)

		val buyr = ordersActor ? buyq
		val selr = ordersActor ? selq
		Future.sequence(Seq(buyr, selr)).map { l =>
			val buyr = l(0).asInstanceOf[OrderList]
			val selr = l(1).asInstanceOf[OrderList]
				<evec_api version="2.0" method="quicklook">
					<quicklook>
						<item>{typeid}</item>
						<itemname>{StaticProvider.typesMap(typeid).name}</itemname>
						<regions>{regionName(regionLimit)}</regions>
						<hours>{setHours}</hours>
						<minqty>{minq}</minqty>
						<sell_orders>{showOrders(Some(selr))}</sell_orders>
						<buy_orders>{showOrders(Some(buyr))}</buy_orders>
					</quicklook>
				</evec_api>
		}
	}
}

case class MarketstatQuery(ctx: RequestContext, dtype: String = "xml")
case class EvemonQuery(ctx: RequestContext)

class MarketStatActor extends Actor with FixedSprayMarshallers with LiftJsonSupport with BaseOrderQuery {

	private val log = LoggerFactory.getLogger(getClass)

	import context.dispatcher

	val liftJsonFormats = DefaultFormats

	override implicit val timeout : Timeout = 60.seconds

	def receive = {

		case EvemonQuery(ctx) =>
			val types = List(34, 35, 36, 37, 38, 39, 40, 11399).map(StaticProvider.typesMap(_))
			val typeFuture = Future.sequence(types.map(evemonMineral(_))).map { mins => <minerals>
				{mins}
			</minerals>
			}
			typeFuture.onComplete {
				case Right(t) => ctx.complete(t)
				case Left(t) => ctx.failWith(t)
			}
		case MarketstatQuery(ctx, dtype) =>
			try {

				def paramUnpack(strings: Seq[String]) : Seq[Long] = {
					if (strings.size > 1) {
						strings.map(_.toLong).distinct
					} else if (strings.size == 1) {
						strings(0).split(",").toList.filter(_.size > 0).map(_.toLong).distinct // Come up with a list of regionlimits comma seperated
					} else {
						Seq[Long]()
					}
				}

				val params = listFromContext(ctx)
				val typeid = paramUnpack(paramsFromQuery("typeid", params))
				if (typeid.foldLeft(true)((n,t) => n && StaticProvider.typesMap.contains(t))) {
					val setHours = singleParam("hours", params) match {
						case Some(x) => x
						case None => 24
					}

					val regionLimit = paramUnpack(paramsFromQuery("regionlimit", params))
					val usesystem = singleParam("usesystem", params)
					val minq = singleParam("minQ", params)

					if (dtype == "json") {
						val future = wrapAsJson(Future.sequence(
							typeid.map(t =>
								getCachedStatistics(t, setHours, regionLimit, usesystem, minq))))
						future.onSuccess { case succ : String => ctx.complete(succ) }
						future.onFailure { case _ => ctx.failWith(_) }
					} else {
						val future = wrapAsXml(Future.sequence(
							typeid.map(t =>
								typeXml(getCachedStatistics(t, setHours, regionLimit, usesystem, minq), t)
							)))
						future.onSuccess { case succ : NodeSeq => ctx.complete(succ) }
						future.onFailure { case _ => ctx.failWith(_) }
					}
				} else {
					ctx.complete(StatusCodes.BadRequest)
				}

			} catch {
				case t : Throwable => ctx.failWith(t)
			}
	}



	def evemonMineral(mineral: MarketType) : Future[NodeSeq] = {
		val q = GetOrdersFor(Some(true), List(mineral.typeid), StaticProvider.empireRegions.map(_.regionid), Nil)
		getCachedStatistic(q).map { s =>
		<mineral>
			<name>{mineral.name}</name>
			<price>{priceString(s.wavg)}</price>
		</mineral>
		}
	}

	/* Produce an XML document of all statistics */
	def typeXml(r: Future[(OrderStatistics, OrderStatistics, OrderStatistics)], typeid: Long) : Future[NodeSeq] = {

		def subGroupXml(alls: OrderStatistics) : NodeSeq = {
				<volume>{alls.volume}</volume>
					<avg>{priceString(alls.wavg)}</avg>
					<max>{priceString(alls.max)}</max>
					<min>{priceString(alls.min)}</min>
					<stddev>{priceString(alls.stdDev)}</stddev>
					<median>{priceString(alls.median)}</median>
					<percentile>{priceString(alls.fivePercent)}</percentile>
			}
		r.map {	r =>
			val buys: OrderStatistics = r._1
			val alls: OrderStatistics = r._2
			val sels: OrderStatistics = r._3

			<type id={typeid.toString}>
				<buy>
					{subGroupXml(buys)}
				</buy>
				<sell>
					{subGroupXml(sels)}
				</sell>
				<all>
					{subGroupXml(alls)}
				</all>
			</type>
		}
	}


	def wrapAsXml(nodes: Future[Seq[NodeSeq]]) : Future[NodeSeq] = nodes.map { nodes => <evec_api version="2.0" method="marketstat_xml">
		<marketstat>
			{nodes}
		</marketstat>
	</evec_api>
	}

	case class BuyAllSell(buy: OrderStatistics, all: OrderStatistics, sell: OrderStatistics)

	def wrapAsJson(types: Future[Seq[(OrderStatistics, OrderStatistics, OrderStatistics)]]) : Future[String] = types.map { types => generate(types.map(u => BuyAllSell(u._1, u._2, u._3))) }


}

//////////////////////////////////////////////////////////////////////////////////////////////
trait APIv2Service extends HttpService with FixedSprayMarshallers {
	this: Actor =>

	val quicklookActor = context.actorOf(Props[QuickLookQuery], name = ActorNames.http_quicklookquery)
	val marketstatActor = context.actorOf(Props[MarketStatActor], name = ActorNames.http_marketstat)
	val olduploadActor = context.actorOf(Props[OldUploadParsingActor], name = ActorNames.http_oldupload)


	import LookupHelper._

	val v2Service : spray.routing.Route = {
		respondWithHeader(RawHeader("Access-Control-Allow-Origin", "*")) {
			path("api/quicklook/onpath/from" / "[^/]+".r / "to" / "[^/]+".r / "fortype" / IntNumber) {
				(fromr, tor, types) =>
					val fromid = lookupSystem(fromr)
					val toid = lookupSystem(tor)
					(get | post) {
						ctx =>
							quicklookActor ! QuickLookPathQuery(ctx, fromid, toid, types)
					}
			} ~
				path("api/quicklook") {

					(get | post) {
						ctx =>
							(quicklookActor ! QuickLookSimpleQuery(ctx))

					}
				} ~ path("api/marketstat" / Rest) {
				dtype =>
					(get | post) {
						ctx =>
							if (dtype.size > 0)
								(marketstatActor ! MarketstatQuery(ctx, dtype))
							else
								(marketstatActor ! MarketstatQuery(ctx))
					}
			} ~ path("api/evemon") {
				(get | post) {
					ctx =>
						(marketstatActor ! EvemonQuery(ctx))
				}
			} ~ path("datainput.py/inputdata") {
				post {
					formFields("typename"?, "userid"?, "data", "typeid"?, "region"?) {
						(typename, userid, data, typeid, region) =>
							olduploadActor ! OldUploadPayload(_, typename, userid, data, typeid, region)
					}
				}
			}
		}
	}
}



