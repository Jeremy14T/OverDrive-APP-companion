package mx.overdrive.companion

/** Fast, real JVM smoke tests for the URL parsing shared by manual paste and QR. */
fun main() {
    val sample = listOf(
        "https://my-car.trycloudflare.com" to ConnectionUrl.Provider.CLOUDFLARE,
        "https://my-car.share.zrok.io" to ConnectionUrl.Provider.ZROK,
        "http://100.64.131.112:8080" to ConnectionUrl.Provider.TAILSCALE,
        "https://car.tailnet.ts.net" to ConnectionUrl.Provider.TAILSCALE,
        "https://my-car.cfargotunnel.com" to ConnectionUrl.Provider.CLOUDFLARE
    )
    for ((url, expected) in sample) {
        val parsedQr = checkNotNull(ConnectionUrl.parseInput(url))
        val parsedManual = checkNotNull(ConnectionUrl.parseInput("  $url  "))
        check(parsedQr.detectedProvider == expected) { "Wrong provider for $url" }
        check(parsedQr == parsedManual) { "QR/manual mismatch: $url" }
        check(ConnectionUrl.validate(parsedQr.url, expected) != null)
    }
    for (url in listOf("https://custom.example.org", "http://192.168.1.10:8080")) {
        val result = checkNotNull(ConnectionUrl.parseInput(url))
        check(result.detectedProvider == null) { "Must request manual choice for $url" }
    }
    for (url in listOf("javascript:alert(1)", "http://public.example.org", "https://user:pass@car.example.org", "http://8.8.8.8:8080")) {
        check(ConnectionUrl.parseInput(url) == null) { "Should reject $url" }
    }
    println("Deteccion QR/manual: 5 reconocidos, 2 ambiguos y 4 inválidos comprobados")
}
