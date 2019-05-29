package jetbrains.datalore.visualization.plot.config.transform

interface SpecChange {
    fun apply(spec: MutableMap<String, Any>, ctx: SpecChangeContext)
    fun isApplicable(spec: Map<String, Any>): Boolean {
        return true
    }
}