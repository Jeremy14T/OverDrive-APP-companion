package mx.overdrive.companion

import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.SSLException

/** One request to a URL the user has explicitly saved. No cookies, redirects or authentication. */
internal object ConnectionProbe {
    sealed class Result {
        data class Answered(val status: Int) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun check(profile: ConnectionStore.Saved): Result {
        val validated = ConnectionUrl.validate(profile.url, profile.provider)
            ?: return Result.Failed("La URL guardada ya no es válida")
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(validated).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 6500
            connection.readTimeout = 6500
            connection.requestMethod = "HEAD"
            connection.useCaches = false
            connection.setRequestProperty("Accept", "text/html,*/*;q=0.1")
            // Even a 401/403/redirect means that some HTTP server answered.
            // It does NOT prove that it is OverDrive or that the login is valid.
            Result.Answered(connection.responseCode)
        } catch (_: SSLException) {
            Result.Failed("Error de certificado o conexión HTTPS")
        } catch (_: java.net.UnknownHostException) {
            Result.Failed("No se encuentra la dirección del túnel")
        } catch (_: java.net.SocketTimeoutException) {
            Result.Failed("Sin respuesta dentro del tiempo esperado")
        } catch (_: java.net.ConnectException) {
            Result.Failed("No se pudo establecer la conexión")
        } catch (_: Exception) {
            Result.Failed("No fue posible comprobar esta dirección")
        } finally {
            connection?.disconnect()
        }
    }
}
