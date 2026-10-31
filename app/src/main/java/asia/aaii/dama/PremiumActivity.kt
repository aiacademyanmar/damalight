package asia.aaii.dama
import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.widget.*
import android.view.Gravity
import asia.aaii.agent.AAIIAppAgent
class PremiumActivity:Activity(){
 private val api=AAIIAppAgent()
 private lateinit var root:LinearLayout
 private lateinit var status:TextView
 private val prefs by lazy{getSharedPreferences("dama_auth",MODE_PRIVATE)}
 private val device by lazy{Settings.Secure.getString(contentResolver,Settings.Secure.ANDROID_ID)?:""}
 private fun field(label:String,password:Boolean=false):EditText {
  val e=EditText(this);e.hint=label;e.setTextColor(Color.WHITE);e.setHintTextColor(Color.LTGRAY)
  if(password)e.inputType=129
  root.addView(e);return e
 }
 private fun button(label:String,action:()->Unit){val b=Button(this);b.text=label;b.setOnClickListener{action()};root.addView(b)}
 private fun message(text:String){status.text=text}
 private fun check(){
  val token=prefs.getString("token","").orEmpty()
  if(token.isEmpty()){message("Please log in");return}
  api.licenseStatus(token,device){r->
   val ok=r.error==null && r.payload?.optBoolean("allowed",false)==true
   message(if(ok)"PREMIUM ACTIVE • "+r.payload?.optString("plan","License verified") else "FREE / ACCESS NOT VERIFIED • "+(r.error?:r.payload?.optString("reason","No active premium")))
  }
 }
 override fun onCreate(state:Bundle?){super.onCreate(state)
  val scroll=ScrollView(this);scroll.setBackgroundColor(0xff061d1e.toInt())
  root=LinearLayout(this);root.orientation=LinearLayout.VERTICAL;root.setPadding(30,30,30,40)
  scroll.addView(root);setContentView(scroll)
  status=TextView(this);status.text="Dhamma Light V21.1 · Premium";status.textSize=20f;status.setTextColor(0xffe5bd6f.toInt());status.gravity=Gravity.CENTER
  root.addView(status)
  val identity=field("Email / Username");val password=field("Password",true)
  button("Login"){api.login(identity.text.toString(),password.text.toString(),device){r->
   val t=r.payload?.optString("token","").orEmpty()
   if(r.error==null && t.isNotEmpty()){prefs.edit().putString("token",t).apply();password.setText("");check()}else message("Login failed: "+(r.error?:"No token"))
  }}
  val name=field("New account name");val email=field("New account email");val newpass=field("New account password",true)
  button("Register"){api.register(name.text.toString(),email.text.toString(),newpass.text.toString(),device){r->message(if(r.error==null)"Registration submitted. Login next." else "Registration failed: "+r.error)}}
  val code=field("Activation code")
  button("Activate code"){val t=prefs.getString("token","").orEmpty();if(t.isEmpty()){message("Login first")}else api.activateLicense(t,device,code.text.toString()){r->if(r.error==null)check() else message("Activation failed: "+r.error)}}
  button("Refresh Premium / Lifetime"){check()}
  button("Premium Content"){val t=prefs.getString("token","").orEmpty();if(t.isEmpty()){message("Login first")}else api.licenseStatus(t,device){r->
   if(r.error==null&&r.payload?.optBoolean("allowed",false)==true)api.getPremiumContent(t,device){p->message(if(p.error==null)p.payload.toString().take(2800) else "Content error: "+p.error)}
   else message("Premium license not authorized")
  }}
  button("Logout"){prefs.edit().remove("token").apply();message("Logged out")}
  if(prefs.contains("token"))check()
 }
 override fun onDestroy(){api.close();super.onDestroy()}
}