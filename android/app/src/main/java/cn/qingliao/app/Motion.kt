package cn.qingliao.app

import android.animation.*
import android.app.Activity
import android.content.res.ColorStateList
import android.os.Build
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.core.view.doOnPreDraw

/** Short, interruptible motion. System animator settings remain the source of truth. */
object Motion {
    val ease=PathInterpolator(.2f,0f,0f,1f)
    fun enabled()=ValueAnimator.areAnimatorsEnabled()
    private fun dp(view:View,n:Float)=n*view.resources.displayMetrics.density
    fun press(view:View,scale:Float=if(view is android.widget.LinearLayout).99f else .96f) {
        if(!enabled()){view.stateListAnimator=null;return}
        fun state(value:Float,duration:Long)=AnimatorSet().apply { playTogether(ObjectAnimator.ofFloat(view,View.SCALE_X,value),ObjectAnimator.ofFloat(view,View.SCALE_Y,value));this.duration=duration;interpolator=ease }
        view.stateListAnimator=StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed,android.R.attr.state_enabled),state(scale,85))
            addState(intArrayOf(),state(1f,140))
        }
    }
    fun reveal(view:View,x:Float=0f,y:Float=7f,duration:Long=200,from:Float=0f) {
        view.animate().cancel()
        if(!enabled()){view.alpha=1f;view.translationX=0f;view.translationY=0f;return}
        view.alpha=from;view.translationX=dp(view,x);view.translationY=dp(view,y)
        view.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(duration).setInterpolator(ease).withLayer().start()
    }
    fun image(view:View) { reveal(view,y=0f,duration=150) }
    fun photo(current:View,previous:android.widget.ImageView,direction:Int) {
        rest(current);rest(previous)
        if(!enabled()){previous.visibility=View.GONE;previous.setImageDrawable(null);return}
        reveal(current,x=18f*direction,y=0f,duration=190)
        previous.animate().alpha(0f).translationX(dp(previous,-9f*direction)).setDuration(190).setInterpolator(ease).withEndAction { previous.visibility=View.GONE;previous.setImageDrawable(null);rest(previous) }.start()
    }
    fun rest(view:View){view.animate().cancel();view.alpha=1f;view.translationX=0f;view.translationY=0f}
    fun swap(host:FrameLayout,next:View,direction:Int) {
        val previous=host.getChildAt(host.childCount-1)
        for(i in host.childCount-1 downTo 0){val child=host.getChildAt(i);rest(child);if(child!==previous)host.removeViewAt(i)}
        next.isClickable=true
        if(previous==null||direction==0||!enabled()){host.removeAllViews();host.addView(next,FrameLayout.LayoutParams(-1,-1));return}
        previous.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        host.addView(next,FrameLayout.LayoutParams(-1,-1))
        next.alpha=0f;next.translationX=dp(next,24f*direction)
        next.doOnPreDraw {
            if(next.parent!==host)return@doOnPreDraw
            previous.animate().alpha(.35f).translationX(dp(previous,-8f*direction)).setDuration(210).setInterpolator(ease).withLayer().start()
            next.animate().alpha(1f).translationX(0f).setDuration(230).setInterpolator(ease).withLayer().withEndAction { if(previous.parent===host)host.removeView(previous) }.start()
        }
    }
    fun bounds(scene:ViewGroup) {
        TransitionManager.endTransitions(scene)
        if(enabled())TransitionManager.beginDelayedTransition(scene,ChangeBounds().apply { duration=220;interpolator=ease;setResizeClip(true) })
    }
    fun end(scene:ViewGroup)=TransitionManager.endTransitions(scene)
    fun sheet(window:Window?){window?.setWindowAnimations(if(enabled())R.style.QingMotionSheet else 0)}
    fun gallery(window:Window?){window?.setWindowAnimations(if(enabled())R.style.QingMotionGallery else 0)}
    fun activityOpen(activity:Activity){activity.overridePendingTransition(if(enabled())R.anim.page_enter else 0,if(enabled())R.anim.page_hold else 0)}
    fun activityClose(activity:Activity){activity.overridePendingTransition(if(enabled())R.anim.page_hold else 0,if(enabled())R.anim.page_exit else 0)}
}
