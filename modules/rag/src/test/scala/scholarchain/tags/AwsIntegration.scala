package scholarchain.tags

/** Tag of every test that talks to real AWS. The build excludes it unless SCHOLARCHAIN_AWS_IT is
  * `true`; the suites also cancel themselves unless an explicit AWS profile is set.
  */
object AwsIntegration extends org.scalatest.Tag("scholarchain.tags.AwsIntegration")
