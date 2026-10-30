package cn.qingliao.app;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Standalone test-APK process: Android SDK only, no tested-app or Kotlin runtime dependency. */
public class AssistTargetActivity extends Activity {
    private int dp(int n) { return (int)(getResources().getDisplayMetrics().density*n+.5f); }
    protected boolean passwordMode(){return false;}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8),dp(48),dp(8),dp(8));
        root.setBackgroundColor(Color.rgb(232,243,255));
        if(passwordMode()) {
            EditText box=new EditText(this);
            box.setHint("合成密码输入");box.setContentDescription("合成密码输入");
            box.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
            root.addView(box,new LinearLayout.LayoutParams(-1,dp(48)));
        } else {
            final int[] taps={0}, longs={0};
            Button tap=new Button(this);tap.setText("点按 0");tap.setContentDescription("合成点按目标");tap.setTextSize(14);
            tap.setOnClickListener(v->tap.setText("点按 "+(++taps[0])));
            root.addView(tap,new LinearLayout.LayoutParams(-1,dp(42)));
            Button hold=new Button(this);hold.setText("长按 0");hold.setContentDescription("合成长按目标");hold.setTextSize(14);
            hold.setOnLongClickListener(v->{hold.setText("长按 "+(++longs[0]));return true;});
            root.addView(hold,new LinearLayout.LayoutParams(-1,dp(42)));
            root.addView(new View(this) {
                float start;int swipes;
                final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
                {setContentDescription("合成滑动目标");setClickable(true);setBackgroundColor(Color.rgb(190,213,250));ink.setColor(Color.rgb(30,50,90));ink.setTextSize(dp(18));}
                @Override protected void onDraw(Canvas c){c.drawText("滑动 "+swipes,dp(16),getHeight()/2f,ink);}
                @Override public boolean onTouchEvent(MotionEvent e){
                    if(e.getActionMasked()==MotionEvent.ACTION_DOWN)start=e.getY();
                    if(e.getActionMasked()==MotionEvent.ACTION_UP){if(Math.abs(e.getY()-start)>dp(16)){swipes++;setContentDescription("合成滑动目标 "+swipes);invalidate();}performClick();}
                    return true;
                }
                @Override public boolean performClick(){super.performClick();return true;}
            },new LinearLayout.LayoutParams(-1,0,1));
        }
        setContentView(root);
    }
}
