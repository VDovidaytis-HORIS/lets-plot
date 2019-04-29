package jetbrains.datalore.visualization.plot.gog.plot.data

import jetbrains.datalore.base.gcommon.base.Preconditions.checkState
import jetbrains.datalore.base.gcommon.base.Strings.isNullOrEmpty
import jetbrains.datalore.base.gcommon.collect.Iterables
import jetbrains.datalore.base.gcommon.collect.Ordering.Companion.natural
import jetbrains.datalore.visualization.plot.gog.common.data.SeriesUtil
import jetbrains.datalore.visualization.plot.gog.core.data.DataFrame
import jetbrains.datalore.visualization.plot.gog.core.data.DataFrame.Builder
import jetbrains.datalore.visualization.plot.gog.core.data.DataFrame.Variable
import jetbrains.datalore.visualization.plot.gog.core.data.DataFrameUtil
import jetbrains.datalore.visualization.plot.gog.core.data.Stat
import jetbrains.datalore.visualization.plot.gog.core.data.StatContext
import jetbrains.datalore.visualization.plot.gog.core.data.stat.Stats
import jetbrains.datalore.visualization.plot.gog.core.render.Aes
import jetbrains.datalore.visualization.plot.gog.core.scale.Scale2
import jetbrains.datalore.visualization.plot.gog.core.scale.ScaleUtil
import jetbrains.datalore.visualization.plot.gog.plot.VarBinding
import jetbrains.datalore.visualization.plot.gog.plot.assemble.PosProvider
import jetbrains.datalore.visualization.plot.gog.plot.assemble.geom.GeomProvider
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.collections.LinkedHashSet

object DataProcessing {

    fun transformOriginals(data: DataFrame, bindings: List<VarBinding>): DataFrame {
        var data = data
        for (binding in bindings) {
            val `var` = binding.`var`
            if (`var`.isOrigin) {
                checkState(data.has(`var`), "Undefined variable $`var`")
                data = DataFrameUtil.applyTransform(data,
                        `var`,
                        binding.aes,
                        binding.scale!!)
            }
        }

        return data
    }

    fun groupsHandled(geomProvider: GeomProvider, posProvider: PosProvider): Boolean {
        return geomProvider.handlesGroups() || posProvider.handlesGroups()
    }

    fun buildStatData(data: DataFrame, stat: Stat, bindings: List<VarBinding>, groupingContext: GroupingContext,
                      facetXVar: String?, facetYVar: String?, statCtx: StatContext): DataAndGroupingContext {
        if (stat === Stats.IDENTITY) {
            return DataAndGroupingContext(
                    Builder.emptyFrame(),
                    groupingContext
            )
        }

        val groups = groupingContext.groupMapper
        val resultSeries = HashMap<Variable, List<Any>>()

        val groupSizeListAfterStat = ArrayList<Int>()

        // if only one group no need to modify
        if (groups === GroupUtil.SINGLE_GROUP) {
            val sd = applyStat(data, stat, bindings, facetXVar, facetYVar, statCtx)
            groupSizeListAfterStat.add(sd.rowCount())
            for (variable in sd.variables()) {
                val list = sd[variable] as List<Any>
                resultSeries[variable] = list
            }
        } else { // add offset to each group
            var lastStatGroupEnd = -1
            for (d in splitByGroup(data, groups)) {
                var sd = applyStat(d, stat, bindings, facetXVar, facetYVar, statCtx)
                if (sd.isEmpty) {
                    continue
                }

                groupSizeListAfterStat.add(sd.rowCount())

                // update 'stat group' to avoid collisions as stat is applied independently to each original data group
                if (sd.has(Stats.GROUP)) {
                    val range = sd.range(Stats.GROUP)
                    if (range != null) {
                        val start = lastStatGroupEnd + 1
                        val offset = start - range.lowerEndpoint().toInt()
                        lastStatGroupEnd = range.upperEndpoint().toInt() + offset
                        if (offset != 0) {
                            val newG = ArrayList<Double>()
                            for (g in sd.getNumeric(Stats.GROUP)) {
                                newG.add(g + offset)
                            }
                            sd = sd.builder().putNumeric(Stats.GROUP, newG).build()
                        }
                    }
                } else { // if stat has ..group.. then groupingVar won't be checked, so no need to update
                    val groupingVar = groupingContext.optionalGroupingVar
                    if (groupingVar != null) {
                        val size = sd[sd.variables().iterator().next()].size
                        val v = d[groupingVar!!][0]
                        sd = sd.builder().put(groupingVar!!, Collections.nCopies<Any>(size, v)).build()
                    }
                }

                // merge results
                for (variable in sd.variables()) {
                    if (!resultSeries.containsKey(variable)) {
                        resultSeries[variable] = ArrayList()
                    }
                    (resultSeries[variable] as MutableList).addAll(sd[variable] as List<Any>)
                }
            }
        }

        val b = Builder()
        for (variable in resultSeries.keys) {
            b.put(variable, resultSeries[variable]!!)
        }

        val dataAfterStat = b.build()
        val groupingContextAfterStat = GroupingContext.withOrderedGroups(dataAfterStat, groupSizeListAfterStat)

        //return dataAfterStat;
        return DataAndGroupingContext(dataAfterStat, groupingContextAfterStat)
    }

    internal fun findOptionalVariable(data: DataFrame, name: String?): Variable? {
        return if (isNullOrEmpty(name))
            null
        else
            DataFrameUtil.findVariableOrFail(data, name!!)
    }

    private fun splitByGroup(data: DataFrame, groups: (Int) -> Int): List<DataFrame> {
        val dataSize = data.rowCount()

        val result = ArrayList<DataFrame>()
        val indicesByGroup = GroupUtil.indicesByGroup(dataSize, groups)
        for (group in indicesByGroup.keys) {
            val indices = indicesByGroup[group]!!
            val b = Builder()

            for (`var` in data.variables()) {
                val serie = data[`var`]
                b.put(`var`, SeriesUtil.pickAtIndices(serie, indices))
            }

            result.add(b.build())
        }

        return result
    }

    /**
     * Server-side only
     */
    private fun applyStat(data: DataFrame, stat: Stat, bindings: List<VarBinding>,
                          facetXVarName: String?, facetYVarName: String?, statCtx: StatContext): DataFrame {

        var statData = stat.apply(data, statCtx)
        val statVariables = statData.variables()
        if (statVariables.isEmpty()) {
            return statData
        }

        // generate new 'input' series to match stat series
        // see: http://docs.ggplot2.org/current/aes_group_order.html
        // "... the group is set to the interaction of all discrete variables in the plot."

        val inverseTransformedStatSeries = inverseTransformContinuousStatData(statData, stat, bindings)

        // generate new series for facet variables

        val statDataSize = statData[statVariables.iterator().next()].size
        val facetVars = HashSet<Variable>()
        for (facetVarName in listOf(facetXVarName, facetYVarName)) {
            if (!isNullOrEmpty(facetVarName)) {
                val facetVar = DataFrameUtil.findVariableOrFail(data, facetVarName!!)
                facetVars.add(facetVar)
                if (!data[facetVar].isEmpty()) {
                    val facetLevel = data[facetVar][0]
                    // generate series for 'facet' variable
                    statData = statData
                            .builder()
                            .put(facetVar, List(statDataSize) { facetLevel })
                            .build()
                }
            }
        }

        // generate new series for input variables

        if (bindings.isEmpty()) {
            return statData
        }

        val newInputSeries = HashMap<Variable, List<*>>()
        for (binding in bindings) {
            val `var` = binding.`var`
            if (`var`.isStat || facetVars.contains(`var`)) {
                continue
            }

            val aes = binding.aes
            if (stat.hasDefaultMapping(aes)) {
                val defaultStatVar = stat.getDefaultMapping(aes)
                val newInputSerie: List<*>
                if (inverseTransformedStatSeries.containsKey(defaultStatVar)) {
                    newInputSerie = inverseTransformedStatSeries[defaultStatVar]!!
                } else {
                    val statSerie = statData.getNumeric(defaultStatVar)
                    newInputSerie = ScaleUtil.inverseTransform(statSerie, binding.scale!!)
                }
                newInputSeries[`var`] = newInputSerie
            } else {
                // Do not override series obtained via 'default stat var'
                if (!newInputSeries.containsKey(`var`)) {
                    val value: Any?
                    if (data.isNumeric(`var`)) {
                        value = SeriesUtil.mean(data.getNumeric(`var`), null)
                    } else {
                        value = SeriesUtil.firstNotNull(data[`var`], null)
                    }
                    val newInputSerie = Collections.nCopies<Any>(statDataSize, value)
                    newInputSeries[`var`] = newInputSerie
                }
            }
        }

        val b = statData.builder()
        for (variable in newInputSeries.keys) {
            b.put(variable, newInputSeries[variable]!!)
        }
        // also update stat series
        for (variable in inverseTransformedStatSeries.keys) {
            b.putNumeric(variable, inverseTransformedStatSeries[variable]!!)
        }

        return b.build()
    }

    private fun inverseTransformContinuousStatData(statData: DataFrame, stat: Stat, bindings: List<VarBinding>): Map<Variable, List<Double>> {
        // inverse transform stat data with continuous domain.
        val scaleByAes = HashMap<Aes<*>, Scale2<*>>()
        val aesByMappedStatVar = HashMap<Variable, Aes<*>>()
        for (aes in Aes.values()) {
            if (stat.hasDefaultMapping(aes)) {
                val defaultStatVar = stat.getDefaultMapping(aes)
                aesByMappedStatVar[defaultStatVar] = aes
            }
        }

        for (binding in bindings) {
            val aes = binding.aes
            val `var` = binding.`var`
            if (`var`.isStat) {
                aesByMappedStatVar[`var`] = aes
            } else if (stat.hasDefaultMapping(aes)) {
                val defaultStatVar = stat.getDefaultMapping(aes)
                aesByMappedStatVar[defaultStatVar] = aes
            }

            if (!binding.isDeferred) {
                val scale = binding.scale
                if (scale!!.isContinuousDomain) {
                    scaleByAes[aes] = scale
                    if (Aes.isPositionalX(aes) && !scaleByAes.containsKey(Aes.X)) {
                        scaleByAes[Aes.X] = scale
                    } else if (Aes.isPositionalY(aes) && !scaleByAes.containsKey(Aes.Y)) {
                        scaleByAes[Aes.Y] = scale
                    }
                }
            }
        }


        val inverseTransformedStatSeries = HashMap<Variable, List<Double>>()
        for (statVar in statData.variables()) {
            if (aesByMappedStatVar.containsKey(statVar)) {
                val aes = aesByMappedStatVar[statVar]!!
                var scale: Scale2<*>? = scaleByAes[aes]
                if (scale == null) {
                    if (Aes.isPositionalX(aes)) {
                        scale = scaleByAes[Aes.X]
                    } else if (Aes.isPositionalY(aes)) {
                        scale = scaleByAes[Aes.Y]
                    }
                }

                if (scale != null) {
                    val statSerie = statData.getNumeric(statVar)
                    val inverseTransformedStatSerie = ScaleUtil.inverseTransformToContinuousDomain(statSerie, scale)
                    inverseTransformedStatSeries[statVar] = inverseTransformedStatSerie
                }
            }
        }
        return inverseTransformedStatSeries
    }

    internal fun computeGroups(data: DataFrame, bindings: List<VarBinding>, groupingVar: Variable?): (Int) -> Int {
        val groupingVariables = getGroupingVariables(data, bindings, groupingVar)

        var currentGroups: List<Int>? = null
        if (groupingVar != null) {
            currentGroups = computeGroups(data[groupingVar])
        }

        for (`var` in groupingVariables) {
            //List<Double> values = data.getNumeric(var);
            val values = data[`var`]
            val groups = computeGroups(values)
            if (currentGroups == null) {
                currentGroups = groups
                continue
            }

            checkState(
                    currentGroups.size == groups.size,
                    "Data series used to compute groups must be equal in size (encountered sizes: " + currentGroups.size + ", " + groups.size + ")"
            )
            val dummies = computeDummyValues(currentGroups, groups)
            currentGroups = computeGroups(dummies)
        }

        return if (currentGroups != null) {
            GroupUtil.wrap(currentGroups)
        } else GroupUtil.SINGLE_GROUP
    }

    private fun computeGroups(values: List<*>): List<Int> {
        val groups = ArrayList<Int>()
        val groupByVal = HashMap<Any, Int>()
        var count = 0
        for (v in values) {
            if (!groupByVal.containsKey(v)) {
                groupByVal[v!!] = count++
            }
            groups.add(groupByVal.get(v)!!)
        }
        return groups
    }

    private fun computeDummyValues(list1: List<Int>, list2: List<Int>): List<Int> {
        if (list1.isEmpty()) return emptyList()

        val limit = 1000

        val max = natural<Int>().max(Iterables.concat(list1, list2))
        checkState(max < limit, "Too many groups: " + max)
        val dummies = ArrayList<Int>()
        val it1 = list1.iterator()
        val it2 = list2.iterator()
        while (it1.hasNext()) {
            val v1 = it1.next()
            val v2 = it2.next()
            val dummy = v1 * limit + v2
            dummies.add(dummy)
        }
        return dummies
    }

    private fun getGroupingVariables(data: DataFrame,
                                     bindings: List<VarBinding>,
                                     explicitGroupingVar: Variable?): Iterable<Variable> {

        // all 'origin' discrete vars + explicitGroupingVar
        val result = LinkedHashSet<Variable>()
        for (binding in bindings) {
            val `var` = binding.`var`
            if (!result.contains(`var`)) {
                if (`var`.isOrigin) {
                    if (!data.isNumeric(`var`) || `var` == explicitGroupingVar) {
                        result.add(`var`)
                    }
                }
            }
        }
        return result
    }

    class DataAndGroupingContext internal constructor(val data: DataFrame, val groupingContext: GroupingContext)

}