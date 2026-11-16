package br.com.ldz.controle;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.Toast;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.Locale;
import java.util.concurrent.*;

/** Manual, integrity-checked updater for official GitHub releases. */
public final class UpdateManager {
    private static final String API="https://api.github.com/repos/ludezam/Controle-LDZ/releases/latest";
    private static final String DOWNLOADS="https://github.com/ludezam/Controle-LDZ/releases/download/";
    private static final String PACKAGE="br.com.ldz.controle";
    private static final String CERT="a782cd40ed0b6d380cb243c4b8cdd120b7de91792cfac1105e273bba4cdd445c";
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService EXEC=Executors.newSingleThreadExecutor();
    private static boolean busy;
    private UpdateManager(){}

    public static void check(Activity activity){
        if(busy){Toast.makeText(activity,"Já estou procurando atualizações.",Toast.LENGTH_SHORT).show();return;}
        busy=true;
        ProgressDialog progress=new ProgressDialog(activity);progress.setMessage("Verificando atualizações…");progress.setCancelable(false);progress.show();
        EXEC.execute(()->{try{
            JSONObject release=latest();String tag=release.getString("tag_name");String version=tag.replaceFirst("^[vV]","");
            JSONArray assets=release.getJSONArray("assets");JSONObject apk=null;
            for(int i=0;i<assets.length();i++){JSONObject a=assets.getJSONObject(i);if(a.getString("name").equals("Controle-LDZ-"+version+".apk")){apk=a;break;}}
            if(apk==null)throw new IOException("A release mais recente não contém o APK esperado.");
            String url=apk.getString("browser_download_url");if(!url.startsWith(DOWNLOADS+tag+"/"))throw new IOException("Endereço do APK não reconhecido.");
            long expectedSize=apk.getLong("size");if(expectedSize<1||expectedSize>40L*1024*1024)throw new IOException("Tamanho do APK fora do esperado.");
            String digest=apk.optString("digest","");if(!digest.matches("(?i)sha256:[0-9a-f]{64}"))throw new IOException("A release não publicou a verificação SHA-256 necessária.");
            File file=download(activity,url,expectedSize,digest.substring(7));
            PackageInfo installed=activity.getPackageManager().getPackageInfo(PACKAGE,0);
            PackageInfo candidate=archiveInfo(activity,file);
            if(!PACKAGE.equals(candidate.packageName))throw new IOException("O pacote baixado não é o Controle LDZ.");
            if(candidate.versionCode<=installed.versionCode){show(activity,progress,"Sem atualização","O Controle LDZ já está na versão mais recente ("+installed.versionName+").",null);return;}
            if(!CERT.equals(signingDigest(candidate)))throw new IOException("A assinatura do APK não corresponde à deste Controle LDZ. A instalação foi bloqueada.");
            show(activity,progress,"Atualização disponível","A versão "+candidate.versionName+" foi verificada e baixada. Deseja instalar agora?",()->install(activity,file));
        }catch(Exception error){show(activity,progress,"Não foi possível atualizar",message(error),null);}});
    }
    private static JSONObject latest()throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(API).openConnection();c.setConnectTimeout(12000);c.setReadTimeout(20000);c.setRequestProperty("Accept","application/vnd.github+json");c.setRequestProperty("User-Agent","ControleLDZ-Android");
        try{int code=c.getResponseCode();if(code!=200)throw new IOException("GitHub respondeu com HTTP "+code+".");ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){copy(in,out,null,Long.MAX_VALUE,null);}return new JSONObject(new String(out.toByteArray(),"UTF-8"));}finally{c.disconnect();}
    }
    private static File download(Activity activity,String url,long size,String sha)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(true);c.setRequestProperty("User-Agent","ControleLDZ-Android");
        File dir=new File(activity.getNoBackupFilesDir(),"updates");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Não foi possível preparar a pasta da atualização.");File part=new File(dir,"Controle-LDZ.apk.part");File done=new File(dir,"Controle-LDZ.apk");
        try{if(c.getResponseCode()!=200)throw new IOException("Falha ao baixar o APK (HTTP "+c.getResponseCode()+").");MessageDigest digest=MessageDigest.getInstance("SHA-256");long count;try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(part)){count=copy(in,out,digest,size,null);out.getFD().sync();}if(count!=size)throw new IOException("O tamanho baixado não corresponde ao publicado no GitHub.");if(!hex(digest.digest()).equalsIgnoreCase(sha))throw new IOException("A verificação SHA-256 falhou.");if(done.exists()&&!done.delete())throw new IOException("Não foi possível substituir o APK temporário.");if(!part.renameTo(done))throw new IOException("Não foi possível finalizar o download.");return done;}finally{c.disconnect();if(part.exists())part.delete();}
    }
    private static long copy(InputStream in,OutputStream out,MessageDigest digest,long limit,Long ignored)throws IOException{byte[] b=new byte[16384];long n=0;int r;while((r=in.read(b))!=-1){n+=r;if(n>limit)throw new IOException("Download maior que o tamanho informado pelo GitHub.");if(digest!=null)digest.update(b,0,r);if(out!=null)out.write(b,0,r);}return n;}
    private static PackageInfo archiveInfo(Context c,File f)throws Exception{PackageManager pm=c.getPackageManager();int flags=PackageManager.GET_SIGNATURES;if(Build.VERSION.SDK_INT>=28)flags|=PackageManager.GET_SIGNING_CERTIFICATES;PackageInfo p=pm.getPackageArchiveInfo(f.getAbsolutePath(),flags);if(p==null)throw new IOException("O arquivo baixado não é um APK válido.");return p;}
    private static String signingDigest(PackageInfo p)throws Exception{android.content.pm.Signature[] sigs=null;if(Build.VERSION.SDK_INT>=28&&p.signingInfo!=null)sigs=p.signingInfo.getApkContentsSigners();if((sigs==null||sigs.length==0)&&p.signatures!=null)sigs=p.signatures;if(sigs==null||sigs.length!=1)throw new IOException("O Android não retornou um único certificado para validar o APK.");byte[] cert=MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray());return hex(cert);}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder(bytes.length*2);for(byte b:bytes)s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
    private static void show(Activity a,ProgressDialog p,String title,String message,Runnable install){MAIN.post(()->{busy=false;if(a.isFinishing()||a.isDestroyed()){if(p.isShowing())p.dismiss();return;}if(p.isShowing())p.dismiss();AlertDialog.Builder b=new AlertDialog.Builder(a).setTitle(title).setMessage(message).setNegativeButton("Fechar",null);if(install!=null)b.setPositiveButton("Instalar",(d,w)->install.run());b.show();});}
    private static void install(Activity a,File apk){try{if(Build.VERSION.SDK_INT>=26&&!a.getPackageManager().canRequestPackageInstalls()){Intent i=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+PACKAGE));a.startActivity(i);Toast.makeText(a,"Permita instalar apps desconhecidos para o Controle LDZ e toque em Buscar atualizações novamente.",Toast.LENGTH_LONG).show();return;}PackageInstaller installer=a.getPackageManager().getPackageInstaller();PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);params.setAppPackageName(PACKAGE);params.setSize(apk.length());int id=installer.createSession(params);Intent callback=new Intent(a,UpdateInstallReceiver.class).setAction(UpdateInstallReceiver.ACTION);int flags=PendingIntent.FLAG_UPDATE_CURRENT;if(Build.VERSION.SDK_INT>=31)flags|=PendingIntent.FLAG_MUTABLE;PendingIntent pending=PendingIntent.getBroadcast(a,id,callback,flags);try(PackageInstaller.Session session=installer.openSession(id)){try(InputStream in=new FileInputStream(apk);OutputStream out=session.openWrite("Controle-LDZ.apk",0,apk.length())){copy(in,out,null,apk.length(),null);session.fsync(out);}session.commit(pending.getIntentSender());}}catch(Exception e){new AlertDialog.Builder(a).setTitle("Falha na instalação").setMessage(message(e)).setPositiveButton("Fechar",null).show();}}
    private static String message(Exception e){String m=e.getMessage();return m==null?"Ocorreu um erro inesperado.":m;}
}
