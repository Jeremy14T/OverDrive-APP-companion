package mx.overdrive.companion

fun main() {
    val cf = ConnectionUrl.Provider.CLOUDFLARE
    val ts = ConnectionUrl.Provider.TAILSCALE
    check(ConnectionUrl.validate("https://car.example.com", cf) != null)
    check(ConnectionUrl.validate("https://my-car.tailnet.ts.net:8443", ts) != null)
    check(ConnectionUrl.validate("http://100.70.1.2:8080", ts) != null)
    check(ConnectionUrl.validate("http://192.168.1.3:8080", ts) != null)
    check(ConnectionUrl.validate("http://8.8.8.8", ts) == null)
    check(ConnectionUrl.validate("http://100.128.2.3:8080", ts) == null)
    check(ConnectionUrl.validate("http://car.example.com", ts) == null)
    check(ConnectionUrl.validate("http://100.70.1.2", cf) == null)
    check(ConnectionUrl.validate("javascript:alert(1)", cf) == null)
    check(ConnectionUrl.validate("https://user:pwd@car.example.com", cf) == null)
    check(ConnectionUrl.sameOrigin("https://car.com", "https://car.com/live"))
    check(!ConnectionUrl.sameOrigin("https://car.com", "https://fake-car.com"))
    check(ConnectionUrl.allowedResource("http://100.70.1.2:8080", "http://100.70.1.2:8080/api"))
    check(!ConnectionUrl.allowedResource("http://100.70.1.2:8080", "http://10.0.0.2:8080/api"))
    println("URL policy: 14 validaciones JVM superadas")
}
