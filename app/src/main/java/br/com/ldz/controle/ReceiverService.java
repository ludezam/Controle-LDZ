package br.com.ldz.controle;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.Settings;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

public final class ReceiverService extends Service {
    public static final int PORT=8765;
    static volatile ReceiverService current;
    final SecureRandom random=new SecureRandom();
    final String pairSecret=randomToken(20);
    final String pin=String.format(Locale.US,"%06d",new SecureRandom().nextInt(1000000));
    LocalAdb adb;
    private LocalHttpServer http;
    private WifiManager.WifiLock standbyWifiLock;
    private PowerManager.WakeLock standbyCpuLock;
    private BroadcastReceiver screenStateReceiver;
    private final ExecutorService connection=Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor commands=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(12));
    private SharedPreferences preferences;
    private volatile long lastConnectAttempt;
    private final Map<String,Long> sessions=new ConcurrentHashMap<>();
    private final Map<String,long[]> attempts=new ConcurrentHashMap<>();
    private volatile String cachedApps="[]";
    private volatile long appsAt=0;
    @Override public void onCreate(){
        super.onCreate();current=this;preferences=getSharedPreferences("receiver",MODE_PRIVATE);adb=new LocalAdb(this);
        try{JSONObject saved=new JSONObject(preferences.getString("sessions","{}"));Iterator<String> keys=saved.keys();while(keys.hasNext()){String key=keys.next();long expiration=saved.optLong(key);if(expiration>System.currentTimeMillis())sessions.put(key,expiration);}}catch(JSONException ignored){}
        NotificationChannel channel=new NotificationChannel("receiver","Controle pela rede local",NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notice=new Notification.Builder(this,"receiver").setSmallIcon(R.drawable.ic_remote).setContentTitle("Controle LDZ disponível").setContentText("Abra para conectar seu celular").setContentIntent(open).setOngoing(true).build();startForeground(41,notice);
        keepWifiAvailableInStandby();
        try{http=new LocalHttpServer(this);http.start();}catch(IOException e){android.util.Log.e("ControleLDZ","Não foi possível iniciar receptor",e);stopSelf();}
        if(preferences.getBoolean("autoconnect",false))authorize();
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){return START_STICKY;}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){current=null;if(screenStateReceiver!=null)try{unregisterReceiver(screenStateReceiver);}catch(IllegalArgumentException ignored){}if(standbyWifiLock!=null&&standbyWifiLock.isHeld())standbyWifiLock.release();if(standbyCpuLock!=null&&standbyCpuLock.isHeld())standbyCpuLock.release();if(http!=null)http.close();commands.shutdownNow();connection.shutdownNow();adb.close();super.onDestroy();}
    private void keepWifiAvailableInStandby(){
        WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);if(wifi==null)return;
        standbyWifiLock=wifi.createWifiLock(WifiManager.WIFI_MODE_FULL,"ControleLDZ:StandbyWifi");standbyWifiLock.setReferenceCounted(false);
        PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);if(power!=null){standbyCpuLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ControleLDZ:StandbyCpu");standbyCpuLock.setReferenceCounted(false);}
        screenStateReceiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))holdStandbyWifi(true);else if(Intent.ACTION_SCREEN_ON.equals(intent.getAction()))holdStandbyWifi(false);}};
        IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_SCREEN_ON);registerReceiver(screenStateReceiver,filter);
        if(power!=null&&!power.isInteractive())holdStandbyWifi(true);
    }
    private void holdStandbyWifi(boolean hold){if(hold){if(standbyWifiLock!=null&&!standbyWifiLock.isHeld())standbyWifiLock.acquire();if(standbyCpuLock!=null&&!standbyCpuLock.isHeld())standbyCpuLock.acquire();}else{if(standbyWifiLock!=null&&standbyWifiLock.isHeld())standbyWifiLock.release();if(standbyCpuLock!=null&&standbyCpuLock.isHeld())standbyCpuLock.release();}}
    void authorize(){
        if("connecting".equals(adb.state)||adb.ready())return;
        long now=System.currentTimeMillis();
        if(now-lastConnectAttempt<5000)return;
        lastConnectAttempt=now;
        try{connection.execute(()->{adb.connect();if(adb.ready())preferences.edit().putBoolean("autoconnect",true).apply();});}
        catch(RejectedExecutionException ignored){}
    }
    String address(){for(String ip:addresses())if(!ip.equals("127.0.0.1"))return "http://"+ip+":"+PORT;return null;}
    static List<String> addresses(){
        List<String> result=new ArrayList<>();
        try{Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();while(interfaces.hasMoreElements()){NetworkInterface net=interfaces.nextElement();if(!net.isUp()||net.isLoopback())continue;Enumeration<InetAddress> values=net.getInetAddresses();while(values.hasMoreElements()){InetAddress ip=values.nextElement();if(ip instanceof Inet4Address&&ip.isSiteLocalAddress())result.add(ip.getHostAddress());}}}catch(SocketException ignored){}
        result.add("127.0.0.1");return result;
    }
    String deviceName(){return android.os.Build.MANUFACTURER.toUpperCase(Locale.ROOT)+" · "+android.os.Build.MODEL;}
    String pairingLink(){String url=address();return url==null?null:url+"/#pair="+pairSecret;}
    synchronized String pair(String supplied,String ip)throws IOException{
        long now=System.currentTimeMillis();long[] attempt=attempts.get(ip);if(attempt==null||now-attempt[1]>60000){if(attempts.size()>128)attempts.clear();attempt=new long[]{0,now};attempts.put(ip,attempt);}if(attempt[0]>=5)throw new IOException("Muitas tentativas. Aguarde um minuto.");
        if(!java.security.MessageDigest.isEqual(pin.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))&&!java.security.MessageDigest.isEqual(pairSecret.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))){attempt[0]++;throw new IOException("Código incorreto. Confira a TV.");}
        attempts.remove(ip);sessions.entrySet().removeIf(entry->entry.getValue()<now);
        if(sessions.size()>=8){String oldest=null;long oldestAt=Long.MAX_VALUE;for(Map.Entry<String,Long> entry:sessions.entrySet())if(entry.getValue()<oldestAt){oldestAt=entry.getValue();oldest=entry.getKey();}if(oldest!=null)sessions.remove(oldest);}
        String token=randomToken(32);sessions.put(token,now+30L*24*60*60*1000);JSONObject object=new JSONObject();try{for(Map.Entry<String,Long> entry:sessions.entrySet())object.put(entry.getKey(),entry.getValue());}catch(JSONException ignored){}preferences.edit().putString("sessions",object.toString()).apply();return token;
    }
    boolean authenticated(String bearer){if(bearer==null||!bearer.startsWith("Bearer "))return false;Long expires=sessions.get(bearer.substring(7));return expires!=null&&expires>System.currentTimeMillis();}
    JSONObject status()throws JSONException{
        if(!adb.ready()&&preferences.getBoolean("autoconnect",false))authorize();
        return new JSONObject().put("name",deviceName()).put("version","1.2").put("ready",adb.ready()).put("state",adb.state).put("detail",adb.detail).put("keyboardConfigured",("br.com.ldz.controle/.RemoteIme").equals(Settings.Secure.getString(getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD)));
    }
    String execute(JSONObject request)throws Exception{
        Future<String> job;
        try{job=commands.submit(()->perform(request));}catch(RejectedExecutionException e){throw new IOException("O controle está ocupado. Tente novamente.");}
        try{return job.get(9,TimeUnit.SECONDS);}catch(TimeoutException e){job.cancel(true);throw new IOException("O Android demorou para responder.");}catch(ExecutionException e){Throwable cause=e.getCause();throw new IOException(cause==null?"Falha no comando":cause.getMessage());}
    }
    private String perform(JSONObject request)throws Exception{
        String action=request.optString("action");
        if(action.equals("text")){String text=request.optString("text");if(text.length()>2000)throw new IOException("Envie até 2.000 caracteres por vez.");if(!RemoteIme.send(text,false))throw new IOException("Selecione um campo na TV e ative o Teclado Controle LDZ.");return "Texto enviado";}
        if(action.equals("submit")){if(RemoteIme.send("",true))return "Pesquisa enviada";action="enter";}
        if(!adb.ready()){if(action.equals("power"))adb.connect();else{authorize();throw new IOException(adb.detail);}}
        if(action.equals("power")){
            String output=sendShell("input keyevent 26");if(!output.isEmpty())throw new IOException("O Android recusou o comando de energia");return "Comando de energia enviado";
        }
        if(action.equals("launch")){
            String pkg=request.optString("package");if(!pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))throw new IOException("Aplicativo inválido");
            Intent intent=getPackageManager().getLeanbackLaunchIntentForPackage(pkg);if(intent==null)intent=getPackageManager().getLaunchIntentForPackage(pkg);if(intent==null||intent.getComponent()==null)throw new IOException("Aplicativo indisponível");
            String component=intent.getComponent().flattenToString();String output=sendShell("am start -n "+quote(component));if(output.contains("Error:")||output.contains("Exception"))throw new IOException("O Android não abriu o aplicativo");return "Aplicativo aberto";
        }
        int key;
        switch(action){case "up":key=19;break;case "down":key=20;break;case "left":key=21;break;case "right":key=22;break;case "ok":key=23;break;case "back":key=4;break;case "home":key=3;break;case "menu":key=82;break;case "volume_up":key=24;break;case "volume_down":key=25;break;case "mute":key=164;break;case "play_pause":key=85;break;case "rewind":key=89;break;case "forward":key=90;break;case "enter":key=66;break;case "delete":key=67;break;default:throw new IOException("Comando desconhecido");}
        String output=sendShell("input keyevent "+key);if(!output.isEmpty())throw new IOException("O Android recusou o comando");return "OK";
    }
    private String sendShell(String command)throws IOException{try{return adb.shell(command);}catch(IOException e){authorize();throw e;}}
    private static String quote(String value){return "'"+value.replace("'","'\\''")+"'";}
    synchronized String apps()throws JSONException{
        if(System.currentTimeMillis()-appsAt<30000)return cachedApps;
        PackageManager manager=getPackageManager();Map<String,ResolveInfo> map=new TreeMap<>();
        for(String category:new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER}){Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(category);for(ResolveInfo info:manager.queryIntentActivities(intent,0))if(!getPackageName().equals(info.activityInfo.packageName))map.put(info.activityInfo.packageName,info);}
        List<ResolveInfo> ordered=new ArrayList<>(map.values());ordered.sort((a,b)->a.loadLabel(manager).toString().compareToIgnoreCase(b.loadLabel(manager).toString()));JSONArray result=new JSONArray();
        for(ResolveInfo info:ordered){if(result.length()>=80)break;JSONObject app=new JSONObject().put("package",info.activityInfo.packageName).put("name",info.loadLabel(manager).toString());try{Drawable icon=info.loadIcon(manager);Bitmap bitmap=Bitmap.createBitmap(72,72,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);icon.setBounds(0,0,72,72);icon.draw(canvas);ByteArrayOutputStream bytes=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes);app.put("icon","data:image/png;base64,"+Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP));bitmap.recycle();}catch(Exception ignored){}result.put(app);}
        cachedApps=result.toString();appsAt=System.currentTimeMillis();return cachedApps;
    }
    private String randomToken(int count){byte[] bytes=new byte[count];random.nextBytes(bytes);return Base64.encodeToString(bytes,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
}
