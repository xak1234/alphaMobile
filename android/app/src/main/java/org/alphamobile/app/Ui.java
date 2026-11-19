package org.alphamobile.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    final Context context;
    final boolean dark,blue;
    final int bg,card,text,sub,line,accent,red=Color.rgb(255,121,144);
    Ui(Context context,boolean dark){this(context,dark,false);}
    Ui(Context context,boolean dark,boolean blue){this.context=context;this.dark=dark;this.blue=blue;
        bg=Color.parseColor(blue?"#08162B":dark?"#02070D":"#EAF2F7");card=Color.parseColor(blue?"#102A49":dark?"#071521":"#F7FCFF");
        text=Color.parseColor(blue?"#EBF3FF":dark?"#E6F7FF":"#0B2537");sub=Color.parseColor(blue?"#A9BEDA":dark?"#8CAEBF":"#466579");
        line=Color.parseColor(blue?"#294969":dark?"#12384C":"#BDD5E2");accent=Color.parseColor(blue?"#8FC7FF":dark?"#59DEFF":"#006A8C");}
    Drawable innerBackground(){return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{Color.parseColor("#102846"),bg,Color.parseColor("#050F21")});}

    int dp(float n){return (int)(context.getResources().getDisplayMetrics().density*n+0.5f);}
    GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    TextView text(String value,int size,int color,boolean bold){TextView v=new TextView(context);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setFontFeatureSettings("kern");if(bold)v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return v;}
    TextView label(String value,int color){TextView v=text(value,10,color,false);v.setTypeface(Typeface.MONOSPACE);v.setLetterSpacing(0.12f);return v;}
    LinearLayout column(){LinearLayout l=new LinearLayout(context);l.setOrientation(LinearLayout.VERTICAL);return l;}
    LinearLayout row(){LinearLayout l=new LinearLayout(context);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    void gap(LinearLayout parent,int h){View v=new View(context);parent.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    LinearLayout card(){LinearLayout l=column();l.setPadding(dp(18),dp(18),dp(18),dp(18));l.setBackground(panel(false));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(14);l.setLayoutParams(p);return l;}
    LinearLayout section(){LinearLayout l=column();l.setPadding(0,dp(14),0,dp(20));return l;}
    void rule(LinearLayout parent){View lineView=new View(context);lineView.setBackgroundColor(line);parent.addView(lineView,new LinearLayout.LayoutParams(-1,dp(1)));}
    LinearLayout entry(LinearLayout parent){LinearLayout item=section();item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2259DEFF),null,shape(Color.WHITE,0)));item.setFocusable(true);parent.addView(item);rule(parent);return item;}
    Button action(String value,Runnable click){Button b=new Button(context);b.setText(value);b.setAllCaps(false);b.setTextColor(accent);b.setTextSize(14);b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);b.setPadding(0,dp(8),dp(8),dp(8));b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2259DEFF),null,shape(Color.WHITE,0)));b.setOnClickListener(v->click.run());return b;}
    Button button(String value,boolean primary,Runnable click){Button b=new Button(context);b.setText(value);b.setAllCaps(false);b.setTextSize(15);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setTextColor(primary?(dark?Color.parseColor("#02151E"):Color.WHITE):text);GradientDrawable fill=shape(primary?accent:(blue?Color.parseColor("#1B3C61"):dark?Color.parseColor("#0C2737"):line),8);fill.setStroke(dp(1),primary?accent:line);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3359DEFF),fill,null));b.setPadding(dp(14),dp(9),dp(14),dp(9));b.setMinHeight(dp(48));b.setOnClickListener(v->click.run());return b;}
    TextView pill(String value,int colour){TextView v=label(value,colour);v.setTextSize(10);v.setPadding(dp(9),dp(5),dp(9),dp(5));GradientDrawable fill=shape((colour&0x00FFFFFF)|0x17000000,5);fill.setStroke(dp(1),(colour&0x00FFFFFF)|0x55000000);v.setBackground(fill);return v;}
    Drawable panel(boolean bright){return new Panel(bright);}
    Drawable backdrop(){return new Backdrop();}
    View moduleArt(String name){return new ModuleArt(context,name);}
    Core core(){return new Core(context);}
    void availability(Button button,boolean online){int color=Color.parseColor(online?"#35D58D":"#CC354D");button.setTextColor(online?Color.parseColor("#032217"):Color.WHITE);GradientDrawable fill=shape(color,8);fill.setStroke(dp(1),color);button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF),fill,null));}
    View icon(String name,int color){return new Glyph(context,name,color);}

    /** Vector surfaces keep the HUD crisp at every Android density. */
    abstract class Surface extends Drawable {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        int opacity=255;
        @Override public void setAlpha(int alpha){opacity=alpha;invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
        int tint(int color,int alpha){return (color&0x00ffffff)|((alpha*opacity/255)<<24);}
    }
    final class Panel extends Surface {
        final boolean bright;final Path outline=new Path();Shader fill;
        Panel(boolean bright){this.bright=bright;}
        @Override protected void onBoundsChange(Rect b){float x=b.left+dp(1),y=b.top+dp(1),r=b.right-dp(1),bottom=b.bottom-dp(1),cut=dp(12);outline.reset();outline.moveTo(x+cut,y);outline.lineTo(r,y);outline.lineTo(r,bottom-cut);outline.lineTo(r-cut,bottom);outline.lineTo(x,bottom);outline.lineTo(x,y+cut);outline.close();fill=new LinearGradient(x,y,r,bottom,blue?(bright?0xF21A3F68:0xF0143050):dark?(bright?0xF20C2638:0xF0091A28):0xF7FFFFFF,blue?0xF007142B:dark?0xF0030D16:0xF0EBF5FA,Shader.TileMode.CLAMP);}
        @Override public void draw(Canvas c){Rect b=getBounds();paint.setStyle(Paint.Style.FILL);paint.setShader(fill);paint.setAlpha(opacity);c.drawPath(outline,paint);paint.setShader(null);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(tint(accent,bright?130:65));c.drawPath(outline,paint);
            paint.setColor(tint(accent,200));paint.setStrokeWidth(dp(2));c.drawLine(b.left+dp(14),b.top+dp(1),b.left+dp(42),b.top+dp(1),paint);c.drawLine(b.right-dp(1),b.bottom-dp(37),b.right-dp(1),b.bottom-dp(15),paint);
            paint.setStrokeWidth(dp(1));paint.setColor(tint(accent,35));c.drawLine(b.left+dp(12),b.bottom-dp(6),b.left+dp(52),b.bottom-dp(6),paint);
        }
    }
    final class Backdrop extends Surface {
        Shader halo;
        @Override protected void onBoundsChange(Rect b){halo=new RadialGradient(b.centerX(),b.height()*.44f,Math.max(1,b.width()*.85f),blue?0xFF1C426F:dark?0xFF08283A:0xFFE0F2FA,bg,Shader.TileMode.CLAMP);}
        @Override public void draw(Canvas c){Rect b=getBounds();paint.setStyle(Paint.Style.FILL);paint.setShader(halo);paint.setAlpha(opacity);c.drawRect(b,paint);paint.setShader(null);paint.setStrokeWidth(dp(.6f));paint.setColor(tint(accent,dark?5:12));
            for(int x=b.left;x<b.right;x+=dp(24))c.drawLine(x,b.top,x,b.bottom,paint);for(int y=b.top;y<b.bottom;y+=dp(24))c.drawLine(b.left,y,b.right,y,paint);
            paint.setColor(tint(accent,35));paint.setStyle(Paint.Style.STROKE);Path path=new Path();for(int side=0;side<2;side++){c.save();if(side==1){c.translate(b.width(),0);c.scale(-1,1);}for(int i=0;i<7;i++){float y=b.height()*.19f+i*dp(65),end=dp(24+(i%3)*13);path.reset();path.moveTo(0,y);path.lineTo(dp(12),y);path.lineTo(end,y+dp(14));path.lineTo(end,y+dp(38));c.drawPath(path,paint);c.drawCircle(end,y+dp(38),dp(2),paint);}c.restore();}
        }
    }
    final class ModuleArt extends View {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);final String name;final Glyph glyph;Shader halo;
        ModuleArt(Context c,String name){super(c);this.name=name;glyph=new Glyph(c,name,accent);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){halo=new RadialGradient(w/2f,h/2f,Math.max(1,Math.min(w,h)/2f),dark?0x5548CFFF:0x2259DEFF,0x0059DEFF,Shader.TileMode.CLAMP);glyph.layout(0,0,dp(29),dp(29));}
        @Override protected void onDraw(Canvas c){float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(getWidth(),getHeight())*.36f;p.setStyle(Paint.Style.FILL);p.setShader(halo);c.drawCircle(cx,cy,r*1.45f,p);p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1));p.setColor((accent&0x00ffffff)|0x55000000);c.drawCircle(cx,cy,r,p);
            p.setColor(accent);for(int i=0;i<4;i++)c.drawArc(cx-r,cy-r,cx+r,cy+r,i*90+7,47,false,p);p.setColor((accent&0x00ffffff)|0x88000000);for(int i=0;i<16;i++){double a=i*Math.PI/8;float end=i%4==0?1.22f:1.12f;c.drawLine(cx+(float)Math.cos(a)*r*1.07f,cy+(float)Math.sin(a)*r*1.07f,cx+(float)Math.cos(a)*r*end,cy+(float)Math.sin(a)*r*end,p);}
            c.save();c.translate(cx-dp(14.5f),cy-dp(14.5f));glyph.draw(c);c.restore();
        }
    }
    final class Core extends View {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);Shader halo,centre;int signal=Color.rgb(255,91,117);boolean online;
        Core(Context c){super(c);setContentDescription("AI connection controls");setFocusable(true);}
        void connected(boolean value){online=value;signal=Color.parseColor(value?"#3DF0A6":"#FF5B75");setContentDescription(value?"AI connected. Connection settings":"AI disconnected. Connection settings");onSizeChanged(getWidth(),getHeight(),getWidth(),getHeight());invalidate();}
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){float radius=Math.max(1,Math.min(w,h)/2f);halo=new RadialGradient(w/2f,h/2f,radius,new int[]{(signal&0xFFFFFF)|0xAA000000,(signal&0xFFFFFF)|0x66000000,signal&0xFFFFFF},new float[]{0,.55f,1},Shader.TileMode.CLAMP);centre=new RadialGradient(w/2f,h/2f,radius*.63f,dark?(online?0xFF104B36:0xFF511B2A):(online?0xFFE8FFF4:0xFFFFEAF0),dark?0xFF03101C:0xFFF7FAFC,Shader.TileMode.CLAMP);}
        @Override protected void onDraw(Canvas c){float x=getWidth()/2f,y=getHeight()/2f,r=Math.min(getWidth(),getHeight())*.32f;p.setStyle(Paint.Style.FILL);p.setShader(halo);c.drawCircle(x,y,r*1.56f,p);p.setShader(null);p.setColor((signal&0x00ffffff)|0x66000000);p.setStrokeWidth(dp(1));c.drawLine(0,y,getWidth(),y,p);c.drawLine(x,0,x,getHeight(),p);p.setAlpha(255);p.setShader(centre);c.drawCircle(x,y,r,p);p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setColor(signal);p.setStrokeWidth(dp(1.2f));c.drawCircle(x,y,r,p);p.setColor((signal&0x00ffffff)|0x99000000);c.drawCircle(x,y,r*.88f,p);
            p.setStrokeWidth(dp(2));for(int i=0;i<4;i++)c.drawArc(x-r*1.16f,y-r*1.16f,x+r*1.16f,y+r*1.16f,i*90+10,50,false,p);p.setStrokeWidth(dp(.8f));for(int i=0;i<40;i++){double a=i*Math.PI/20;float outer=i%5==0?1.34f:1.27f;c.drawLine(x+(float)Math.cos(a)*r*1.2f,y+(float)Math.sin(a)*r*1.2f,x+(float)Math.cos(a)*r*outer,y+(float)Math.sin(a)*r*outer,p);}
            p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));p.setTextSize(dp(36));p.setColor(text);c.drawText("AI",x,y+dp(7),p);p.setTypeface(Typeface.MONOSPACE);p.setTextSize(dp(6.5f));p.setColor(signal);c.drawText(online?"L I N K E D":"O F F L I N E",x,y+dp(21),p);
        }
    }
    final class Glyph extends View {
        final Paint p=new Paint(3);final Path path=new Path();final String name;final int color;
        Glyph(Context c,String name,int color){super(c);this.name=name;this.color=color;setContentDescription(name);}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);canvas.save();canvas.scale(getWidth()/24f,getHeight()/24f);p.setColor(color);p.setStrokeWidth(1.65f);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);path.reset();
            switch(name){
                case "Calls":case "phone":path.moveTo(5,3);path.lineTo(9,3);path.lineTo(10.5f,8);path.lineTo(8,10);path.cubicTo(9,13,11,15,14,16);path.lineTo(16,13.5f);path.lineTo(21,15);path.lineTo(21,19);path.cubicTo(21,24,1,13,3,5);path.close();canvas.drawPath(path,p);break;
                case "Messages":canvas.drawRoundRect(3,4,21,18,4,4,p);canvas.drawLine(7,18,6,22,p);canvas.drawLine(6,22,12,18,p);canvas.drawLine(7,9,17,9,p);canvas.drawLine(7,13,14,13,p);break;
                case "Agents":canvas.drawCircle(9,8,3,p);canvas.drawArc(3,13,15,23,180,180,false,p);canvas.drawArc(14,5,20,11,270,180,false,p);canvas.drawArc(13,13,23,23,265,95,false,p);break;
                case "Tasks":canvas.drawRoundRect(4,5,20,22,3,3,p);canvas.drawLine(8,2,8,7,p);canvas.drawLine(16,2,16,7,p);canvas.drawLine(4,10,20,10,p);canvas.drawLine(8,15,10,17,p);canvas.drawLine(10,17,16,13,p);break;
                case "Settings":canvas.drawCircle(12,12,4,p);canvas.drawCircle(12,12,8,p);for(int i=0;i<8;i++){double a=i*Math.PI/4;canvas.drawLine(12+8*(float)Math.cos(a),12+8*(float)Math.sin(a),12+10*(float)Math.cos(a),12+10*(float)Math.sin(a),p);}break;
                case "mic":canvas.drawRoundRect(9,3,15,15,3,3,p);canvas.drawArc(6,8,18,19,0,180,false,p);canvas.drawLine(12,19,12,22,p);break;
                case "back":canvas.drawLine(19,12,5,12,p);canvas.drawLine(5,12,11,6,p);canvas.drawLine(5,12,11,18,p);break;
                case "wave":for(int i=0;i<5;i++){float h=(float)(4+6*Math.sin(i*1.3));canvas.drawLine(4+i*4,12-h/2,4+i*4,12+h/2,p);}break;
                default:canvas.drawCircle(12,12,8,p);
            }canvas.restore();}
    }
}
