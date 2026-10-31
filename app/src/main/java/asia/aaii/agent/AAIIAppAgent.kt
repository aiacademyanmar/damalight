package asia.aaii.agent
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
class AAIIAppAgent : AutoCloseable {
 data class ApiResult(val payload: JSONObject?, val error: String?, val httpStatus: Int=0)
 private val executor=Executors.newSingleThreadExecutor()
 private val handler=Handler(Looper.getMainLooper())
 private fun request(path:String, body:JSONObject, token:String?, cb:(ApiResult)->Unit) {
  executor.execute {
   val result=try {
    val conn=URL(AgentConfig.SERVER_BASE_URL+"/api/"+path+"?app_key="+AgentConfig.APP_KEY).openConnection() as HttpURLConnection
    try {
     conn.requestMethod="POST";conn.connectTimeout=10000;conn.readTimeout=15000;conn.doOutput=true
     conn.setRequestProperty("Content-Type","application/json")
     conn.setRequestProperty("Accept","application/json")
     if(!token.isNullOrBlank())conn.setRequestProperty("Authorization","Bearer "+token)
     conn.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}
     val status=conn.responseCode
     val value=(if(status in 200..299)conn.inputStream else conn.errorStream)?.bufferedReader()?.use{it.readText()}?:"{}"
     val json=JSONObject(value)
     ApiResult(json,if(status in 200..299)null else json.optString("error","HTTP "+status),status)
    } finally { conn.disconnect() }
   }catch(e:Exception){ApiResult(null,e.message?: "Network error")}
   handler.post{cb(result)}
  }
 }
 fun login(identity:String,password:String,device:String,cb:(ApiResult)->Unit) = request("login.php",JSONObject().put("app_key",AgentConfig.APP_KEY).put("identifier",identity).put("email",identity).put("password",password).put("device_id",device),null,cb)
 fun register(name:String,email:String,password:String,device:String,cb:(ApiResult)->Unit) = request("register.php",JSONObject().put("app_key",AgentConfig.APP_KEY).put("name",name).put("email",email).put("password",password).put("device_id",device),null,cb)
 fun licenseStatus(token:String,device:String,cb:(ApiResult)->Unit) = request("managed_app_license.php",JSONObject().put("app_key",AgentConfig.APP_KEY).put("action","status").put("device_id",device),token,cb)
 fun activateLicense(token:String,device:String,code:String,cb:(ApiResult)->Unit) = request("managed_app_license.php",JSONObject().put("app_key",AgentConfig.APP_KEY).put("action","activate").put("activation_code",code).put("device_id",device),token,cb)
 fun getPremiumContent(token:String,device:String,cb:(ApiResult)->Unit) = request("managed_app_premium_content.php",JSONObject().put("app_key",AgentConfig.APP_KEY).put("device_id",device),token,cb)
 override fun close(){executor.shutdownNow()}
}