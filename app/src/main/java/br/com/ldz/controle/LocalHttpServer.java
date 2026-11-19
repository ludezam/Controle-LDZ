package br.com.ldz.controle;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** HTTP local de escopo limitado, sem CORS e sem endpoints de shell arbitrario. */
final class LocalHttpServer {
    private final ReceiverService service;
    private final ServerSocket server;
    private final ThreadPoolExecutor clients=new ThreadPoolExecutor(3,8,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(24));
    private volatile boolean closed=false;
    LocalHttpServer(ReceiverService service)throws IOException{this.service=service;server=new ServerSocket();server.setReuseAddress(true);server.bind(new InetSocketAddress(ReceiverService.PORT));}
    void start(){Thread thread=new Thread(()->{while(!closed)try{Socket client=server.accept();client.setSoTimeout(6000);try{clients.execute(()->handle(client));}catch(RejectedExecutionException e){client.close();}}catch(IOException ignored){if(closed)break;}},"ControleLDZ-HTTP");thread.setDaemon(true);thread.start();}
    void close(){closed=true;try{server.close();}catch(IOException ignored){}clients.shutdownNow();}
    private void handle(Socket socket){
        try(Socket client=socket){
            InputStream input=client.getInputStream();OutputStream output=client.getOutputStream();String requestLine=line(input,2048);if(requestLine==null)return;String[] first=requestLine.split(" ");if(first.length!=3){reply(output,400,"application/json",jsonError("Requisição inválida"));return;}
            Map<String,String> headers=new HashMap<>();int total=0;String header;
            while((header=line(input,4096))!=null&&!header.isEmpty()){total+=header.length();int colon=header.indexOf(':');if(total>16384||colon<1){reply(output,400,"application/json",jsonError("Cabeçalho inválido"));return;}String key=header.substring(0,colon).trim().toLowerCase(Locale.ROOT);if(headers.containsKey(key)){reply(output,400,"application/json",jsonError("Cabeçalho duplicado"));return;}headers.put(key,header.substring(colon+1).trim());}
            String host=headers.get("host");boolean validHost=false;for(String address:ReceiverService.addresses())if((address+":"+ReceiverService.PORT).equals(host))validHost=true;
            if(!validHost){reply(output,403,"application/json",jsonError("Endereço de conexão inválido"));return;}
            String method=first[0],path=first[1].split("\\?",2)[0];
            if(headers.containsKey("transfer-encoding")){reply(output,400,"application/json",jsonError("Formato não suportado"));return;}
            boolean photoUpload=path.equals("/api/tv/photos")&&method.equals("POST");
            int size;try{size=Integer.parseInt(headers.getOrDefault("content-length","0"));}catch(NumberFormatException e){size=-1;}if(size<0||size>(photoUpload?12*1024*1024:16384)){reply(output,413,"application/json",jsonError("Requisição muito grande"));return;}
            String contentType=headers.getOrDefault("content-type","").toLowerCase(Locale.ROOT);
            if(method.equals("POST")&&!(photoUpload?contentType.startsWith("image/"):contentType.startsWith("application/json"))){reply(output,415,"application/json",jsonError(photoUpload?"Envie um arquivo de imagem":"Use JSON"));return;}
            byte[] body=new byte[size];int read=0;while(read<size){int n=input.read(body,read,size-read);if(n<0)throw new EOFException();read+=n;}
            // Consumir o corpo limitado antes de recusar a origem evita um RST
            // que poderia interromper a resposta HTTP de erro no cliente.
            if(method.equals("POST")&&(!("http://"+host).equals(headers.get("origin"))||"cross-site".equals(headers.get("sec-fetch-site")))){reply(output,403,"application/json",jsonError("Origem inválida"));return;}
            try{
                if(path.equals("/api/pair")&&method.equals("POST")){JSONObject request=new JSONObject(new String(body,StandardCharsets.UTF_8));String token=service.pair(request.optString("code"),client.getInetAddress().getHostAddress());reply(output,200,"application/json",bytes(new JSONObject().put("token",token).toString()));return;}
                if(path.startsWith("/api/")){
                    if(!service.authenticated(headers.get("authorization"))){reply(output,401,"application/json",jsonError("Conecte este celular usando o código da TV"));return;}
                    if(path.equals("/api/status")&&method.equals("GET"))reply(output,200,"application/json",bytes(service.status().toString()));
                    else if(path.equals("/api/apps")&&method.equals("GET"))reply(output,200,"application/json",bytes(service.apps()));
                    else if(path.equals("/api/tv/available")&&method.equals("GET"))reply(output,200,"application/json",bytes(new JSONObject().put("available",service.tvCustomizationAvailable()).toString()));
                    else if(path.equals("/api/tv/settings")&&method.equals("GET"))reply(output,200,"application/json",bytes(service.tvSettings().toString()));
                    else if(path.equals("/api/tv/settings")&&method.equals("POST"))reply(output,200,"application/json",bytes(service.setTvSetting(new JSONObject(new String(body,StandardCharsets.UTF_8))).toString()));
                    else if(path.equals("/api/tv/photos")&&method.equals("POST"))reply(output,200,"application/json",bytes(service.importTvPhoto(body,contentType).toString()));
                    else if(path.equals("/api/tv/photos")&&method.equals("DELETE"))reply(output,200,"application/json",bytes(service.clearTvPhotos().toString()));
                    else if(path.equals("/api/command")&&method.equals("POST")){String message=service.execute(new JSONObject(new String(body,StandardCharsets.UTF_8)));reply(output,200,"application/json",bytes(new JSONObject().put("ok",true).put("message",message).toString()));}
                    else reply(output,404,"application/json",jsonError("Rota indisponível"));return;
                }
                if(!method.equals("GET")&&!method.equals("HEAD")){reply(output,405,"application/json",jsonError("Método não permitido"));return;}
                String asset,type;
                switch(path){case "/":case "/index.html":asset="web/index.html";type="text/html; charset=utf-8";break;case "/personalizacao.html":asset="web/personalizacao.html";type="text/html; charset=utf-8";break;case "/app.js":asset="web/app.js";type="application/javascript; charset=utf-8";break;case "/personalizacao.js":asset="web/personalizacao.js";type="application/javascript; charset=utf-8";break;case "/personalizacao.css":asset="web/personalizacao.css";type="text/css; charset=utf-8";break;case "/style.css":asset="web/style.css";type="text/css; charset=utf-8";break;case "/manifest.webmanifest":asset="web/manifest.webmanifest";type="application/manifest+json";break;case "/icon.svg":asset="web/icon.svg";type="image/svg+xml";break;default:reply(output,404,"application/json",jsonError("Não encontrado"));return;}
                ByteArrayOutputStream data=new ByteArrayOutputStream();try(InputStream file=service.getAssets().open(asset)){byte[] buffer=new byte[8192];int n;while((n=file.read(buffer))!=-1)data.write(buffer,0,n);}reply(output,200,type,method.equals("HEAD")?new byte[0]:data.toByteArray());
            }catch(JSONException e){reply(output,400,"application/json",jsonError("Dados inválidos"));}catch(Exception e){reply(output,409,"application/json",jsonError(e.getMessage()==null?"Falha ao executar comando":e.getMessage()));}
        }catch(IOException ignored){}
    }
    private static String line(InputStream input,int max)throws IOException{ByteArrayOutputStream data=new ByteArrayOutputStream();int value;while((value=input.read())!=-1){if(value=='\n')break;if(value!='\r')data.write(value);if(data.size()>max)throw new IOException("Linha muito longa");}if(value==-1&&data.size()==0)return null;return new String(data.toByteArray(),StandardCharsets.US_ASCII);}
    private static byte[] jsonError(String message){try{return bytes(new JSONObject().put("error",message).toString());}catch(JSONException e){return bytes("{}");}}
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static void reply(OutputStream output,int status,String type,byte[] body)throws IOException{
        String reason=status==200?"OK":status==401?"Unauthorized":status==404?"Not Found":"Request Failed";
        String headers="HTTP/1.1 "+status+" "+reason+"\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'\r\n\r\n";output.write(headers.getBytes(StandardCharsets.US_ASCII));output.write(body);output.flush();
    }
}
