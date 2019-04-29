package jetbrains.datalore.visualization.plot.gog.plot.event3

import jetbrains.datalore.base.gcommon.base.Preconditions.checkArgument
import jetbrains.datalore.base.geometry.DoubleRectangle
import jetbrains.datalore.base.geometry.DoubleVector
import jetbrains.datalore.visualization.plot.core.GeomKind
import jetbrains.datalore.visualization.plot.gog.config.event3.GeomTargetInteraction.TooltipAesSpec
import jetbrains.datalore.visualization.plot.gog.core.event3.GeomTarget
import jetbrains.datalore.visualization.plot.gog.core.event3.GeomTargetLocator
import jetbrains.datalore.visualization.plot.gog.core.event3.HitShape.Kind.*
import jetbrains.datalore.visualization.plot.gog.plot.event3.MathUtil.ClosestPointChecker
import jetbrains.datalore.visualization.plot.gog.plot.event3.TargetProjector.*

internal class GeomTargetLocatorImpl(private val myGeomKind: GeomKind, lookupSpec: GeomTargetLocator.LookupSpec, private val myTooltipAesSpec: TooltipAesSpec, targetPrototypes: List<GeomTargetPrototype>) : GeomTargetLocator {

    private val myLocatorLookupSpace: GeomTargetLocator.LookupSpace
    private val myLocatorLookupStrategy: GeomTargetLocator.LookupStrategy
    private val myTargetDetector: TargetDetector
    private val myTargets = ArrayList<Target>()

    private val collectingStrategy: Collector.CollectingStrategy
        get() {
            if (myLocatorLookupSpace === GeomTargetLocator.LookupSpace.X) {
                return Collector.CollectingStrategy.APPEND
            }

            if (myLocatorLookupStrategy === GeomTargetLocator.LookupStrategy.HOVER) {
                return Collector.CollectingStrategy.APPEND
            }

            return if (myLocatorLookupStrategy === GeomTargetLocator.LookupStrategy.NONE || myLocatorLookupSpace === GeomTargetLocator.LookupSpace.NONE) {
                Collector.CollectingStrategy.IGNORE
            } else Collector.CollectingStrategy.REPLACE

        }

    init {
        myLocatorLookupSpace = lookupSpec.lookupSpace
        myLocatorLookupStrategy = lookupSpec.lookupStrategy
        myTargetDetector = TargetDetector(myLocatorLookupSpace, myLocatorLookupStrategy)

        val targetProjector = TargetProjector(myLocatorLookupSpace)
        for (targetPrototype in targetPrototypes) {
            myTargets.add(
                    Target(
                            targetProjector.project(targetPrototype),
                            targetPrototype
                    )
            )
        }
    }

    private fun addFoundTarget(collector: Collector<GeomTarget>, targets: MutableList<GeomTargetLocator.LocatedTargets>) {
        if (collector.size() == 0) {
            return
        }

        targets.add(
                GeomTargetLocator.LocatedTargets(
                        collector.collection(),
                        // Distance can be negative when lookup space is X
                        // In this case use 0.0 as a distance - we have a direct hit.
                        Math.max(0.0, collector.closestPointChecker.distance),
                        myGeomKind,
                        myTooltipAesSpec
                )
        )
    }

    override fun findTargets(coord: DoubleVector): GeomTargetLocator.LocatedTargets? {
        if (myTargets.isEmpty()) {
            return null
        }

        val rectCollector = Collector<GeomTarget>(coord, collectingStrategy)
        val pointCollector = Collector<GeomTarget>(coord, collectingStrategy)
        val pathCollector = Collector<GeomTarget>(coord, collectingStrategy)

        // Should always replace because of polygon with holes - only top should have tooltip.
        val polygonCollector = Collector<GeomTarget>(coord, Collector.CollectingStrategy.REPLACE)

        for (target in myTargets) {
            when (target.prototype.hitShape.kind) {
                RECT -> processRect(coord, target, rectCollector)

                POINT -> processPoint(coord, target, pointCollector)

                PATH -> processPath(coord, target, pathCollector)

                POLYGON -> processPolygon(coord, target, polygonCollector)
            }
        }

        val locatedTargets = ArrayList<GeomTargetLocator.LocatedTargets>()

        addFoundTarget(pathCollector, locatedTargets)
        addFoundTarget(rectCollector, locatedTargets)
        addFoundTarget(pointCollector, locatedTargets)
        addFoundTarget(polygonCollector, locatedTargets)

        return getClosestTarget(locatedTargets)
    }

    private fun getClosestTarget(locatedTargetList: List<GeomTargetLocator.LocatedTargets>): GeomTargetLocator.LocatedTargets? {
        if (locatedTargetList.isEmpty()) {
            return null
        }

        var closestTargets: GeomTargetLocator.LocatedTargets = locatedTargetList[0]
        checkArgument(closestTargets.distance >= 0)

        for (locatedTargets in locatedTargetList) {
            if (locatedTargets.distance < closestTargets.distance) {
                closestTargets = locatedTargets
            }
        }
        return closestTargets
    }

    private fun processRect(coord: DoubleVector, target: Target, resultCollector: Collector<GeomTarget>) {
        if (myTargetDetector.checkRect(coord, target.rectProjection, resultCollector.closestPointChecker)) {

            val rect = target.prototype.hitShape.rect
            resultCollector.collect(
                    target.prototype.crateGeomTarget(
                            rect.origin.add(DoubleVector(rect.width / 2, 0.0)),
                            getKeyForSingleObjectGeometry(target.prototype)
                    )
            )
        }
    }

    private fun processPolygon(coord: DoubleVector, target: Target, resultCollector: Collector<GeomTarget>) {
        if (myTargetDetector.checkPolygon(coord, target.polygonProjection, resultCollector.closestPointChecker)) {

            resultCollector.collect(
                    target.prototype.crateGeomTarget(
                            coord,
                            getKeyForSingleObjectGeometry(target.prototype)
                    )
            )
        }
    }

    private fun processPoint(coord: DoubleVector, target: Target, resultCollector: Collector<GeomTarget>) {
        if (myTargetDetector.checkPoint(coord, target.pointProjection, resultCollector.closestPointChecker)) {

            resultCollector.collect(
                    target.prototype.crateGeomTarget(
                            target.prototype.hitShape.point.center,
                            getKeyForSingleObjectGeometry(target.prototype)
                    )
            )
        }
    }

    private fun processPath(coord: DoubleVector, target: Target, resultCollector: Collector<GeomTarget>) {
        // When searching single point from all targets (REPLACE) - should search nearest projection between every path target.
        // When searching points for every target (APPEND) - should reset nearest point between every path target.
        val pointChecker = if (collectingStrategy == Collector.CollectingStrategy.APPEND)
            ClosestPointChecker(coord)
        else
            resultCollector.closestPointChecker

        val hitPoint = myTargetDetector.checkPath(coord, target.pathProjection, pointChecker)
        if (hitPoint != null) {
            resultCollector.collect(
                    target.prototype.crateGeomTarget(
                            hitPoint.originalCoord,
                            hitPoint.index
                    )
            )
        }
    }

    private fun getKeyForSingleObjectGeometry(targetPrototype: GeomTargetPrototype): Int {
        return targetPrototype.indexMapper(0)
    }

    internal class RingXY(val edges: List<DoubleVector>, val bbox: DoubleRectangle)

    internal class Target(private val myTargetProjection: TargetProjection?, val prototype: GeomTargetPrototype) {

        val pointProjection: PointTargetProjection
            get() = myTargetProjection as PointTargetProjection

        val rectProjection: RectTargetProjection
            get() = myTargetProjection as RectTargetProjection

        val polygonProjection: PolygonTargetProjection
            get() = myTargetProjection as PolygonTargetProjection

        val pathProjection: PathTargetProjection
            get() = myTargetProjection as PathTargetProjection
    }

    internal class Collector<T>(cursor: DoubleVector, private val myStrategy: CollectingStrategy) {
        private val result = ArrayList<T>()
        val closestPointChecker: ClosestPointChecker

        init {
            closestPointChecker = ClosestPointChecker(cursor)
        }

        fun collect(data: T) {
            when (myStrategy) {
                CollectingStrategy.APPEND -> add(data)
                CollectingStrategy.REPLACE -> replace(data)
                CollectingStrategy.IGNORE -> return
            }
        }

        fun collection(): List<T> {
            return result
        }

        fun size(): Int {
            return result.size
        }

        private fun add(data: T) {
            result.add(data)
        }

        private fun replace(locationData: T) {
            result.clear()
            result.add(locationData)
        }

        internal enum class CollectingStrategy {
            APPEND,
            REPLACE,
            IGNORE
        }
    }
}