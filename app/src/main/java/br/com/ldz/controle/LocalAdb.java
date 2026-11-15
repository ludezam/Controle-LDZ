package br.com.ldz.controle;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.*;
import javax.crypto.Cipher;

/** ADB TCP local autenticado. Nenhum comando arbitrario e exposto pela API HTTP. */
final class LocalAdb {
    private static final int CNXN=0x4e584e43, AUTH=0x48545541, OPEN=0x4e45504f, OKAY=0x59414b4f, WRTE=0x45545257, CLSE=0x45534c43;
    private final Context context;
    private volatile Socket socket; private InputStream input; private OutputStream output;
    private int sequence = 1;
    volatile String state = "disconnected", detail = "Autorize o controle na TV";
    LocalAdb(Context context) { this.context = context; }
    boolean ready() { return "ready".equals(state); }
    synchronized void connect() {
        if (ready()) return;
        state="connecting"; detail="Conectando ao Android…";
        try {
            closeSocket(); KeyPair key = keyPair(); socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", 5555), 2500);
            socket.setTcpNoDelay(true); socket.setSoTimeout(90000);
            input=socket.getInputStream(); output=socket.getOutputStream();
            packet(CNXN,0x01000000,4096,"host::\0".getBytes(StandardCharsets.UTF_8));
            boolean signed=false, offered=false;
            for (int attempts=0; attempts<8; attempts++) {
                Message message=read();
                if (message.command==CNXN) { socket.setSoTimeout(7000); state="ready"; detail="Controle do Android autorizado"; return; }
                if (message.command!=AUTH || message.arg0!=1) throw new IOException("Resposta de autenticação inesperada");
                if (!signed) {
                    byte[] prefix={0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14};
                    ByteArrayOutputStream digest=new ByteArrayOutputStream(); digest.write(prefix); digest.write(message.data);
                    Cipher cipher=Cipher.getInstance("RSA/ECB/PKCS1Padding"); cipher.init(Cipher.ENCRYPT_MODE,key.getPrivate());
                    packet(AUTH,2,0,cipher.doFinal(digest.toByteArray())); signed=true;
                } else if (!offered) {
                    detail="Confirme a autorização de depuração na TV";
                    packet(AUTH,3,0,publicKey((RSAPublicKey)key.getPublic())); offered=true;
                } else throw new IOException("Autorização não concedida na TV");
            }
            throw new IOException("Não foi possível autenticar");
        } catch (Exception e) { closeSocket(); state="disconnected"; detail=e instanceof SocketTimeoutException ? "A autorização não foi confirmada. Tente novamente." : "Não foi possível conectar ao Android. Verifique a depuração local."; }
    }
    synchronized String shell(String command) throws IOException {
        if (!ready()) throw new IOException(detail);
        int local=sequence++, remote=0; ByteArrayOutputStream response=new ByteArrayOutputStream();
        try {
            packet(OPEN,local,0,("shell:"+command+'\0').getBytes(StandardCharsets.UTF_8));
            for(int count=0;count<512;count++) {
                Message m=read();
                if(m.arg1!=local) throw new IOException("Canal ADB inesperado");
                if(m.command==OKAY) remote=m.arg0;
                else if(m.command==WRTE) { remote=m.arg0; if(response.size()<32768)response.write(m.data,0,Math.min(m.data.length,32768-response.size())); packet(OKAY,local,remote,new byte[0]); }
                else if(m.command==CLSE) { packet(CLSE,local,m.arg0,new byte[0]); return new String(response.toByteArray(),StandardCharsets.UTF_8).trim(); }
                else throw new IOException("Resposta ADB inválida");
            }
            throw new IOException("Resposta excedeu o limite");
        } catch(IOException e) { closeSocket(); state="disconnected"; detail="Conexão com Android interrompida. Autorize novamente na TV."; throw e; }
    }
    void close() { closeSocket(); state="disconnected"; }
    private void closeSocket() { Socket current=socket;socket=null;if(current!=null)try{current.close();}catch(IOException ignored){} }
    private KeyPair keyPair() throws Exception {
        SharedPreferences preferences=context.getSharedPreferences("local-adb",Context.MODE_PRIVATE);
        String privateKey=preferences.getString("private",null), publicKey=preferences.getString("public",null);
        if(privateKey!=null && publicKey!=null) {
            KeyFactory factory=KeyFactory.getInstance("RSA");
            return new KeyPair(factory.generatePublic(new X509EncodedKeySpec(Base64.decode(publicKey,Base64.DEFAULT))),factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(privateKey,Base64.DEFAULT))));
        }
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair pair=generator.generateKeyPair();
        if(!preferences.edit().putString("private",Base64.encodeToString(pair.getPrivate().getEncoded(),Base64.NO_WRAP)).putString("public",Base64.encodeToString(pair.getPublic().getEncoded(),Base64.NO_WRAP)).commit())throw new IOException("Não foi possível guardar a identidade do receptor");
        return pair;
    }
    private static byte[] publicKey(RSAPublicKey key) {
        BigInteger modulus=key.getModulus(), base=BigInteger.ONE.shiftLeft(32);
        ByteBuffer data=ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(64);data.putInt(modulus.modInverse(base).negate().intValue());data.put(little(modulus,256));data.put(little(BigInteger.ONE.shiftLeft(4096).mod(modulus),256));data.putInt(key.getPublicExponent().intValue());
        return (Base64.encodeToString(data.array(),Base64.NO_WRAP)+" controle-ldz@android\0").getBytes(StandardCharsets.UTF_8);
    }
    private static byte[] little(BigInteger integer,int length) { byte[] source=integer.toByteArray(), result=new byte[length];for(int i=0;i<length&&i<source.length;i++)result[i]=source[source.length-1-i];return result; }
    private void packet(int command,int a,int b,byte[] payload) throws IOException {
        int sum=0;for(byte value:payload)sum+=value&255;
        ByteBuffer header=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);header.putInt(command).putInt(a).putInt(b).putInt(payload.length).putInt(sum).putInt(command^0xffffffff);
        output.write(header.array());output.write(payload);output.flush();
    }
    private Message read() throws IOException {
        ByteBuffer header=ByteBuffer.wrap(exact(24)).order(ByteOrder.LITTLE_ENDIAN);Message m=new Message();m.command=header.getInt();m.arg0=header.getInt();m.arg1=header.getInt();int length=header.getInt(),checksum=header.getInt(),magic=header.getInt();
        if(length<0||length>1024*1024||magic!=(m.command^0xffffffff))throw new IOException("Pacote ADB inválido");
        m.data=exact(length);int sum=0;for(byte value:m.data)sum+=value&255;if(checksum!=sum)throw new IOException("Checksum ADB inválido");return m;
    }
    private byte[] exact(int length) throws IOException {byte[] data=new byte[length];int count=0;while(count<length){int n=input.read(data,count,length-count);if(n<0)throw new EOFException("Conexão encerrada");count+=n;}return data;}
    private static final class Message {int command,arg0,arg1;byte[] data;}
}
