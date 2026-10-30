package cn.qingliao.app;

/** Separate task target so returning to the ordinary fixture cannot focus an old password field. */
public class AssistPasswordTargetActivity extends AssistTargetActivity {
    @Override protected boolean passwordMode(){return true;}
}
