package br.com.ldz.controle;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import java.io.InputStream;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int INK=0xff1d1d1f;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView address,code,status,device;
    private ImageView qr;
    private String previousLink="";
    private final Runnable refresh=new Runnable(){public void run(){update();handler.postDelayed(this,1000);}};
    @Override public void onCreate(Bundle bundle){super.onCreate(bundle);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);startForegroundService(new Intent(this,ReceiverService.class));build();}
    @Override public void onResume(){super.onResume();handler.post(refresh);}
    @Override public void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    private TextView text(String value,int color,boolean bold){TextView view=new TextView(this);view.setText(value);view.setTextColor(color);view.setIncludeFontPadding(false);view.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));return view;}
    private GradientDrawable glass(float radius,boolean focused){GradientDrawable shape=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xeeffffff,0x70ffffff,0xb0ffffff});shape.setCornerRadius(radius);shape.setStroke(focused?3:2,focused?0xff0071e3:0xeeffffff);return shape;}
    private Button button(String value,View.OnClickListener listener){Button button=new Button(this);button.setText(value);button.setTextColor(INK);button.setAllCaps(false);button.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));button.setMinHeight(0);button.setMinimumHeight(0);button.setPadding(12,0,12,0);StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_focused},glass(32,true));states.addState(new int[]{android.R.attr.state_pressed},glass(32,true));states.addState(new int[]{},glass(32,false));button.setBackground(states);button.setElevation(3);button.setOnClickListener(listener);return button;}
    private void build(){
        DesignLayout root=new DesignLayout();root.setBackgroundColor(0xfff5f5f7);setContentView(root);
        ImageView background=new ImageView(this);background.setScaleType(ImageView.ScaleType.FIT_XY);background.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        try(InputStream stream=getAssets().open("receiver/remote-liquid-glass.png")){background.setImageBitmap(BitmapFactory.decodeStream(stream));}catch(Exception ignored){}
        root.place(background,0,0,1280,720,0);
        root.place(text("Controle LDZ",INK,true),50,34,260,42,32);
        root.place(text("1.3.1",0xff727277,false),281,43,64,30,21);
        device=text("●  TCL · Smart TV",INK,false);device.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);root.place(device,920,32,312,42,23);
        TextView title=text("Seu celular.\nSeu controle.",INK,true);title.setLineSpacing(0,1.02f);root.place(title,50,150,465,172,62);
        TextView instruction=text("Conecte-se à mesma rede\ne leia o QR code.",INK,false);instruction.setLineSpacing(3,1);root.place(instruction,50,323,435,78,28);
        address=text("Conectando à rede…",INK,false);address.setGravity(Gravity.CENTER);address.setSingleLine(true);address.setBackground(glass(32,false));address.setElevation(2);root.place(address,44,422,410,68,25);
        code=text("",INK,false);root.place(code,50,516,460,37,22);
        View card=new View(this);card.setBackground(glass(42,false));card.setElevation(4);root.place(card,861,125,378,393,0);
        qr=new ImageView(this);qr.setBackgroundColor(Color.WHITE);qr.setPadding(12,12,12,12);qr.setScaleType(ImageView.ScaleType.FIT_CENTER);qr.setContentDescription("QR code para conectar o celular");qr.setElevation(6);root.place(qr,908,168,284,284,0);
        TextView caption=text("Abra a câmera do celular",INK,false);caption.setGravity(Gravity.CENTER);caption.setElevation(6);root.place(caption,870,470,360,35,21);
        status=text("Preparando receptor…",0xff258758,true);status.setGravity(Gravity.CENTER);root.place(status,856,541,388,60,22);
        Button authorize=button("Autorizar controle",v->{ReceiverService service=ReceiverService.current;if(service!=null)service.authorize();});root.place(authorize,36,615,255,68,20);
        Button keyboard=button("Teclado",v->keyboardOptions());root.place(keyboard,313,615,180,68,20);
        Button settings=button("Configurações",v->settings());root.place(settings,515,615,242,68,20);
        TextView note=text("Na sua rede. Sem nuvem.",INK,false);note.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);root.place(note,930,629,304,44,19);
        authorize.requestFocus();
    }
    private void keyboardOptions(){new AlertDialog.Builder(this).setTitle("Teclado do celular").setItems(new String[]{"Ativar Teclado Controle LDZ","Selecionar teclado","Testar digitação"},(dialog,which)->{if(which==0)startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));else if(which==1)((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker();else testKeyboard();}).setNegativeButton("Fechar",null).show();}
    private void testKeyboard(){EditText input=new EditText(this);input.setHint("Digite pelo celular");input.setSingleLine(true);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Teste de digitação").setView(input).setPositiveButton("Fechar",null).create();dialog.setOnShowListener(d->{input.requestFocus();dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);});dialog.show();}
    private void settings(){new AlertDialog.Builder(this).setTitle("Configurações").setItems(new String[]{"Buscar atualizações","Teclado do celular","Sobre o Controle LDZ"},(d,w)->{if(w==0)UpdateManager.check(this);else if(w==1)keyboardOptions();else about();}).setNegativeButton("Fechar",null).show();}
    private void about(){ReceiverService service=ReceiverService.current;String info="Versão 1.3.1\n\n"+(service==null?"Preparando receptor…":service.deviceName()+"\n"+service.address()+"\n\n"+service.adb.detail)+"\n\nO celular deve estar na mesma rede da TV. O receptor permanece disponível ao voltar à home.";new AlertDialog.Builder(this).setTitle("Controle LDZ").setMessage(info).setPositiveButton("Fechar",null).setNeutralButton("Teclado",(d,w)->keyboardOptions()).show();}
    private void setTextIfChanged(TextView view,String value){if(!value.contentEquals(view.getText()))view.setText(value);}
    private void update(){ReceiverService service=ReceiverService.current;if(service==null){setTextIfChanged(status,"Iniciando receptor…");return;}
        String url=service.address();setTextIfChanged(address,url==null?"Conecte a TV à rede":url);setTextIfChanged(code,"Código de conexão: "+service.pin.substring(0,3)+" "+service.pin.substring(3));setTextIfChanged(device,"●  "+service.deviceName());setTextIfChanged(status,service.adb.ready()?"● Controle autorizado":service.adb.detail);status.setTextColor(service.adb.ready()?0xff238422:0xff727277);
        String link=service.pairingLink();if(link!=null&&!link.equals(previousLink)){try{Map<EncodeHintType,Object> options=new EnumMap<>(EncodeHintType.class);options.put(EncodeHintType.MARGIN,2);BitMatrix matrix=new MultiFormatWriter().encode(link,BarcodeFormat.QR_CODE,512,512,options);Bitmap bitmap=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);int[] pixels=new int[512*512];for(int y=0;y<512;y++)for(int x=0;x<512;x++)pixels[y*512+x]=matrix.get(x,y)?INK:Color.WHITE;bitmap.setPixels(pixels,0,512,0,0,512,512);qr.setImageBitmap(bitmap);previousLink=link;}catch(WriterException ignored){setTextIfChanged(status,"Não foi possível gerar QR code");}}
    }
    private final class DesignLayout extends FrameLayout {
        private final List<Placement> placements=new ArrayList<>();
        DesignLayout(){super(MainActivity.this);}
        void place(View view,int x,int y,int width,int height,int size){placements.add(new Placement(view,x,y,width,height,size));addView(view,new FrameLayout.LayoutParams(width,height));}
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);float scale=Math.min(w/1280f,h/720f),ox=(w-1280*scale)/2,oy=(h-720*scale)/2;for(Placement item:placements){FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)item.view.getLayoutParams();lp.width=Math.round(item.width*scale);lp.height=Math.round(item.height*scale);lp.leftMargin=Math.round(ox+item.x*scale);lp.topMargin=Math.round(oy+item.y*scale);item.view.setLayoutParams(lp);if(item.size>0&&item.view instanceof TextView)((TextView)item.view).setTextSize(TypedValue.COMPLEX_UNIT_PX,item.size*scale);}}
    }
    private static final class Placement {final View view;final int x,y,width,height,size;Placement(View v,int x,int y,int w,int h,int s){view=v;this.x=x;this.y=y;width=w;height=h;size=s;}}
}
