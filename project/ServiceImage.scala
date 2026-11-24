/** One container image per service entrypoint. Build structure, owned by build-engineer. */
final class ServiceImage(
    val image: String,
    val mainClass: String,
    val healthPort: Int,
    val withPrompts: Boolean
)
