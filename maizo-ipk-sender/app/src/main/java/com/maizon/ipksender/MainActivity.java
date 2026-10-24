package com.maizon.ipksender;

import android.app.*;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import com.jcraft.jsch.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
  private static final int CHOOSE = 17, BG=0xff0e1629, CARD=0xff18253a, TEXT=0xffe7f3ff, MINT=0xff43dfc2;
  private EditText host, password;
  private Button choose, send;
  private TextView filename, status;
  private ProgressBar progress;
  private Uri selected;
  private boolean busy = false;
  private long fileSize = -1;

  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);
    ScrollView sc = new ScrollView(this); sc.setBackgroundColor(BG);
    LinearLayout page = new LinearLayout(this);
    page.setOrientation(LinearLayout.VERTICAL); page.setPadding(px(20),px(32),px(20),px(24));
    sc.addView(page); setContentView(sc);
    page.addView(text("MAIZON  /  WEBOS",13,MINT,true));
    page.addView(text("IPK Sender",34,TEXT,true));
    page.addView(text("Android → LG webOS • Wi-Fi local",14,0xffa4afc8,false));
    LinearLayout card=new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL); card.setPadding(px(18),px(24),px(18),px(22));
    card.setBackground(rounded(CARD));
    LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(-1,-2); cl.topMargin=px(25); page.addView(card,cl);
    card.addView(text("01   IP DA TELEVISÃO",13,0xffa4afc8,true));
    host=input("192.168.1.100",false);
    host.setText(getPreferences(0).getString("ip","")); card.addView(host,spaced(7));
    card.addView(text("SSH: porta 22   •   usuário root",12,0xffa4afc8,false));
    card.addView(text("02   SENHA SSH",13,0xffa4afc8,true),spaced(22));
    password=input("Senha SSH",true); password.setText("alpine"); card.addView(password,spaced(7));
    card.addView(text("A senha não é salva.",12,0xffa4afc8,false));
    card.addView(text("03   ARQUIVO IPK",13,0xffa4afc8,true),spaced(22));
    choose=btn("Selecionar arquivo .IPK",false); card.addView(choose,spaced(8));
    choose.setOnClickListener(v -> pick());
    filename=text("Nenhum IPK selecionado",13,0xffb4c8d9,false);
    card.addView(filename,spaced(8));
    send=btn("ENVIAR E INSTALAR",true); card.addView(send,spaced(22));
    send.setOnClickListener(v -> start());
    progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
    progress.setMax(100); progress.setProgressTintList(android.content.res.ColorStateList.valueOf(MINT));
    LinearLayout.LayoutParams pp=spaced(20); pp.height=px(7); card.addView(progress,pp);
    status=text("Pronto. Ative o SSH no Homebrew Channel da TV.",13,0xffc5d2e3,false);
    card.addView(status,spaced(12));
    page.addView(text("A TV e o celular precisam estar na mesma rede. Na primeira conexão confirme a chave SSH da sua própria TV.",12,0xff9fafc5,false),spaced(20));
  }
  private int px(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
  private GradientDrawable rounded(int c){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(px(15));return d;}
  private LinearLayout.LayoutParams spaced(int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=px(top);return p;}
  private TextView text(String s,int sz,int col,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sz);t.setTextColor(col);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
  private EditText input(String hint,boolean secret){EditText e=new EditText(this);e.setTextSize(16);e.setTextColor(TEXT);e.setHintTextColor(0xff8da2ba);e.setSingleLine(true);e.setHint(hint);e.setPadding(px(14),px(12),px(14),px(12));e.setBackground(rounded(BG));if(secret)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);return e;}
  private Button btn(String s,boolean main){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(main?BG:TEXT);b.setTextSize(15);b.setTypeface(null,Typeface.BOLD);b.setBackground(rounded(main?MINT:0xff33435a));return b;}
  private void info(String s){runOnUiThread(()->status.setText(s));}
  private void pick(){
    Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(i,CHOOSE);
  }
  @Override protected void onActivityResult(int req,int result,Intent data){
    super.onActivityResult(req,result,data);
    if(req!=CHOOSE||result!=RESULT_OK||data==null)return;
    Uri uri=data.getData(); if(uri==null)return;
    String name=""; long length=-1;
    try(Cursor c=getContentResolver().query(uri,null,null,null,null)){
      if(c!=null && c.moveToFirst()){
        int ni=c.getColumnIndex(OpenableColumns.DISPLAY_NAME), si=c.getColumnIndex(OpenableColumns.SIZE);
        if(ni>=0)name=c.getString(ni);if(si>=0&&!c.isNull(si))length=c.getLong(si);
      }
    }catch(Exception ignored){}
    if(name==null||!name.toLowerCase(Locale.ROOT).endsWith(".ipk")){info("Selecione um arquivo com extensão .ipk");return;}
    selected=uri;fileSize=length;filename.setText(name+(length>0?" ("+(length/1024)+" KB)":""));info("Arquivo selecionado.");
  }
  private boolean validIp(String ip){
    String[] p=ip.split("\\.");if(p.length!=4)return false;
    for(String x:p)try{if(x.isEmpty()||x.length()>3||!x.matches("[0-9]+")||Integer.parseInt(x)>255)return false;}catch(Exception e){return false;}
    return true;
  }
  private void start(){
    if(busy)return;
    String ip=host.getText().toString().trim(),pw=password.getText().toString();
    if(!validIp(ip)){info("Informe o endereço IPv4 da TV.");return;}
    if(selected==null){info("Escolha primeiro o arquivo .ipk.");return;}
    if(pw.isEmpty()){info("Informe a senha SSH.");return;}
    getPreferences(0).edit().putString("ip",ip).apply();
    busy=true;choose.setEnabled(false);send.setEnabled(false);progress.setIndeterminate(true);
    Uri uri=selected;long length=fileSize;
    new Thread(()->{try{transfer(ip,pw,uri,length);}catch(Exception e){info("Falha: "+e.getMessage());}finally{runOnUiThread(()->{busy=false;choose.setEnabled(true);send.setEnabled(true);progress.setIndeterminate(false);});}},"send-ipk").start();
  }
  private void transfer(String ip,String pw,Uri uri,long expected) throws Exception {
    Session session=null;
    String remote="/tmp/maizo_"+UUID.randomUUID().toString().replace("-","")+".ipk";
    try{
      JSch js=new JSch();
      File hosts=new File(getFilesDir(),"known_hosts");
      if(!hosts.exists()&&!hosts.createNewFile())throw new IOException("Falha ao criar known_hosts");
      js.setKnownHosts(hosts.getAbsolutePath());
      session=js.getSession("root",ip,22);session.setPassword(pw);
      session.setUserInfo(new SSHPrompt(pw));
      session.setConfig("StrictHostKeyChecking","ask");
      session.setConfig("PreferredAuthentications","password,keyboard-interactive");
      session.setTimeout(20000);info("Conectando à TV…");session.connect(15000);
      info("Conectado. Enviando IPK…");
      ChannelExec ch=(ChannelExec)session.openChannel("exec");
      ch.setCommand("cat > "+remote);ch.setInputStream(null);
      OutputStream output=ch.getOutputStream();
      ch.connect(15000);
      long count=0,then=0;
      try(InputStream input=getContentResolver().openInputStream(uri)){
        if(input==null)throw new IOException("Não foi possível ler o arquivo");
        byte[] buf=new byte[32768];int n;
        while((n=input.read(buf))!=-1){
          output.write(buf,0,n);count+=n;
          if(SystemClock.elapsedRealtime()-then>200){
            long done=count;runOnUiThread(()->{progress.setIndeterminate(expected<=0);if(expected>0)progress.setProgress((int)Math.min(100,done*100/expected));status.setText("Enviando "+(done/1024)+" KB"+(expected>0?" / "+(expected/1024)+" KB":""));});
            then=SystemClock.elapsedRealtime();
          }
        }
      }finally{output.close();}
      waitEnd(ch,45000);
      int code=ch.getExitStatus();ch.disconnect();
      if(code!=0)throw new IOException("Falha no envio SSH (código "+code+")");
      if(count<=0)throw new IOException("Arquivo vazio");
      String size=exec(session,"wc -c < "+remote,10000).trim();
      if(!size.equals(Long.toString(count)))throw new IOException("Tamanho transferido divergente ("+size+" vs "+count+")");
      info("Enviado! Instalando na TV…");runOnUiThread(()->progress.setIndeterminate(true));
      JSONObject json=new JSONObject();json.put("id","com.ares.defaultName");json.put("ipkUrl",remote);json.put("subscribe",true);
      String cmd="luna-send -i -f luna://com.webos.appInstallService/dev/install '"+json.toString()+"'";
      String reply=exec(session,cmd,180000);
      if(reply.matches("(?s).*\\\"returnValue\\\"\\s*:\\s*false.*")||reply.contains("errorCode"))throw new IOException("Instalador rejeitou: "+reply.substring(Math.max(0,reply.length()-500)));
      if(reply.matches("(?s).*\\\"statusValue\\\"\\s*:\\s*30\\b.*")){
        exec(session,"rm -f "+remote,10000);
        runOnUiThread(()->{progress.setIndeterminate(false);progress.setProgress(100);});
        info("✅ INSTALADO! Verifique o menu de aplicativos da LG.");
      }else{
        info("Instalação solicitada, mas sem confirmação final. Arquivo salvo em "+remote+" na TV. Resposta: "+reply.substring(Math.max(0,reply.length()-600)));
      }
    }finally{if(session!=null)session.disconnect();}
  }
  private void waitEnd(ChannelExec ch,long ms)throws Exception{
    long limit=SystemClock.elapsedRealtime()+ms;
    while(!ch.isClosed()&&SystemClock.elapsedRealtime()<limit)SystemClock.sleep(90);
    if(!ch.isClosed())throw new IOException("Tempo limite no envio IPK");
  }
  private String exec(Session s,String command,long timeout)throws Exception{
    ChannelExec c=(ChannelExec)s.openChannel("exec");
    c.setCommand(command);c.setInputStream(null);
    InputStream out=c.getInputStream(),err=c.getExtInputStream();
    StringBuilder sb=new StringBuilder();long limit=SystemClock.elapsedRealtime()+timeout;
    try{
      c.connect(15000);
      byte[] buf=new byte[4096];
      while(SystemClock.elapsedRealtime()<limit){
        while(out.available()>0){int n=out.read(buf);if(n>0)sb.append(new String(buf,0,n,"UTF-8"));}
        while(err.available()>0){int n=err.read(buf);if(n>0)sb.append(new String(buf,0,n,"UTF-8"));}
        if(c.isClosed()){while(out.available()>0){int n=out.read(buf);if(n>0)sb.append(new String(buf,0,n,"UTF-8"));} if(c.getExitStatus()!=0)throw new IOException(command.split(" ")[0]+" falhou: "+sb.toString());return sb.toString();}
        SystemClock.sleep(85);
      }
      throw new IOException("Tempo limite ao instalar IPK");
    }finally{c.disconnect();}
  }
  private final class SSHPrompt implements UserInfo {
    String p;SSHPrompt(String p){this.p=p;}
    public String getPassphrase(){return null;}public String getPassword(){return p;}
    public boolean promptPassword(String s){return true;}public boolean promptPassphrase(String s){return false;}
    public void showMessage(String s){info(s);}
    public boolean promptYesNo(String question){
      CountDownLatch latch=new CountDownLatch(1);final boolean[] ok={false};
      runOnUiThread(()->new AlertDialog.Builder(MainActivity.this)
        .setTitle("Confirmar identidade SSH")
        .setMessage(question+"\n\nAceite somente se for sua TV LG.")
        .setPositiveButton("Confiar", (d,w)->{ok[0]=true;latch.countDown();})
        .setNegativeButton("Cancelar",(d,w)->latch.countDown())
        .setOnCancelListener(d->latch.countDown()).show());
      try{return latch.await(60,TimeUnit.SECONDS)&&ok[0];}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
    }
  }
}
