package dev.lawlan.runline.runner

/**
 * The rule for what may cross between the host and a run's class loader: only classes the JDK
 * itself defines (boot or platform loader), alone or inside JDK collections. Anything else would
 * pull a class of one side into the other and keep a run's class loader reachable.
 */
internal object BoundaryTypes {
  private val platform = ClassLoader.getPlatformClassLoader()

  fun requireJdkOnly(value: Any?) {
    when (value) {
      null -> Unit
      is Map<*, *> -> {
        requireJdkClass(value)
        value.forEach { (k, v) ->
          requireJdkOnly(k)
          requireJdkOnly(v)
        }
      }
      is Iterable<*> -> {
        requireJdkClass(value)
        value.forEach(::requireJdkOnly)
      }
      else -> requireJdkClass(value)
    }
  }

  private fun requireJdkClass(value: Any) {
    val loader = value.javaClass.classLoader
    require(loader == null || loader === platform) {
      "${value.javaClass.name} is not a JDK type and may not cross the run boundary"
    }
  }
}
