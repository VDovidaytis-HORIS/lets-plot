/*
 * Copyright (c) 2020. JetBrains s.r.o.
 * Use of this source code is governed by the MIT license that can be found in the LICENSE file.
 */

package plot.config

import jetbrains.datalore.plot.base.Aes
import jetbrains.datalore.plot.builder.map.GeoPositionField
import jetbrains.datalore.plot.config.LayerConfig
import jetbrains.datalore.plot.config.Option
import jetbrains.datalore.plot.config.Option.PlotBase.DATA
import jetbrains.datalore.plot.config.Option.PlotBase.MAPPING
import jetbrains.datalore.plot.config.PlotConfigClientSideUtil
import jetbrains.datalore.plot.server.config.PlotConfigServerSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeomInteractionBuilderCreationTest {

    private val data = mapOf(
        Aes.X.name to listOf(1.0),
        Aes.Y.name to listOf(1.0)
    )

    @Test
    fun checkAesListsForTooltipTest() {
        val mappedData = data + mapOf(
            Aes.COLOR.name to listOf('a')
        )
        val plotOpts = mutableMapOf(
            MAPPING to mappedData,
            Option.Plot.LAYERS to listOf(
                mapOf(
                    Option.Layer.GEOM to Option.GeomName.HISTOGRAM
                )
            )
        )
        val layerConfig = createLayerConfig(plotOpts)

        val builder = PlotConfigClientSideUtil.createGeomInteractionBuilder(
            layerConfig,
            emptyList(),
            false
        )

        val expectedAxisList = listOf(Aes.X)
        val expectedAesListCount = (layerConfig.geomProto.renders() - expectedAxisList).size

        assertAesListCount(expectedAxisList.size, builder.axisAesListForTooltip)
        assertAesListCount(expectedAesListCount, builder.aesListForTooltip)
    }


    @Test
    fun shouldSkipMapIdMapping() {
        val mappedData = data + mapOf(
           Aes.MAP_ID.name to listOf('a')
        )
        val plotOpts = mutableMapOf(
            MAPPING to mappedData,
            Option.Plot.LAYERS to listOf(
                mapOf(
                    Option.Layer.GEOM to Option.GeomName.POINT
                )
            )
        )
        val layerConfig = createLayerConfig(plotOpts)

        val builder = PlotConfigClientSideUtil.createGeomInteractionBuilder(
            layerConfig,
            emptyList(),
            false
        )

        val expectedAxisList = listOf(Aes.X, Aes.Y)
        // without Aes.MAP_ID:
        val expectedAesListCount = (layerConfig.geomProto.renders() - expectedAxisList).size - 1

        assertAesListCount(expectedAxisList.size, builder.axisAesListForTooltip)
        assertAesListCount(expectedAesListCount, builder.aesListForTooltip)
    }

     @Test
    fun shouldSkipMapIdMappingAndAxisVisibilityIsFalse() {
       val mappedData = data + mapOf(
           Aes.MAP_ID.name to listOf('a')
       )

        val plotOpts = mutableMapOf(
            MAPPING to mappedData,
            Option.Plot.LAYERS to listOf(
                mapOf(
                    Option.Layer.GEOM to Option.GeomName.POLYGON
                )
            )
        )
        val layerConfig = createLayerConfig(plotOpts)

        val builder = PlotConfigClientSideUtil.createGeomInteractionBuilder(
            layerConfig,
            emptyList(),
            false
        )

        // builder's axis tooltip visibility is false:
        val expectedAxisCount = 0
        // without Aes.MAP_ID:
        val expectedAesListCount = (layerConfig.geomProto.renders() - listOf(Aes.X, Aes.Y)).size - 1

        assertAesListCount(expectedAxisCount, builder.axisAesListForTooltip)
        assertAesListCount(expectedAesListCount, builder.aesListForTooltip)

    }

    @Test
    fun shouldNotDuplicateVarToAxisAndGenericTooltip() {
        val mappedData = mapOf(
            Aes.X.name to listOf(4.0),
            Aes.FILL.name to Aes.X.name
        )

        val plotOpts = mutableMapOf(
            MAPPING to mappedData,
            Option.Plot.LAYERS to listOf(
                mapOf(
                    Option.Layer.GEOM to Option.GeomName.HISTOGRAM
                )
            )
        )
        val layerConfig = createLayerConfig(plotOpts)

        val builder = PlotConfigClientSideUtil.createGeomInteractionBuilder(
            layerConfig,
            emptyList(),
            false
        )

        val expectedAxisList = listOf(Aes.X)
        // without duplicated Aes.FILL:
        val expectedAesListCount = (layerConfig.geomProto.renders() - expectedAxisList).size - 1

        assertAesListCount(expectedAxisList.size, builder.axisAesListForTooltip)
        assertAesListCount(expectedAesListCount, builder.aesListForTooltip)
    }

    @Test
    fun shouldSkipAutoGeneratedMappings() {

        val GEOMETRIES = listOf(
            "{\"type: \"Point\", \"coordinates\":[-10, -20]}",
            "{\"type: \"Point\", \"coordinates\":[-30, -40]}",
            "{\"type: \"Point\", \"coordinates\":[-50, -60]}"
        )
        val geomData = mapOf(
            "name" to listOf("a", "b", "c"),
            "value" to listOf("1", "2", "3"),
            "coord" to GEOMETRIES
        )
        val GEO_DATA_FRAME_META = mapOf(
            Option.Meta.GeoDataFrame.TAG to mapOf(
                Option.Meta.GeoDataFrame.GEOMETRY to "coord"
            )
        )
        val plotOpts = mutableMapOf(
            Option.Meta.KIND to Option.Meta.Kind.PLOT,
            Option.Plot.LAYERS to listOf(
                mutableMapOf(
                    Option.Layer.GEOM to Option.GeomName.POLYGON,
                    DATA to geomData,
                    Option.Meta.DATA_META to GEO_DATA_FRAME_META,
                    MAPPING to mapOf(Aes.FILL.name to "name")
                )
            )
        )
        val layerConfig = createLayerConfig(plotOpts)

        assertTrue(layerConfig.hasVarBinding(GeoPositionField.DATA_JOIN_KEY_COLUMN))

        val builder = PlotConfigClientSideUtil.createGeomInteractionBuilder(
            layerConfig,
            emptyList(),
            false
        )

        assertAesListCount(0, builder.axisAesListForTooltip)
        assertAesListCount(1, builder.aesListForTooltip)
    }

    private fun createLayerConfig(plotOpts: MutableMap<String, Any>): LayerConfig {
        val plotSpec = PlotConfigServerSide.processTransform(plotOpts)
        return PlotConfigServerSide(plotSpec).layerConfigs.first()
    }

    internal fun assertAesListCount(expectedCount: Int, aesList: List<Aes<*>>) {
        assertEquals(expectedCount, aesList.size)
    }
}