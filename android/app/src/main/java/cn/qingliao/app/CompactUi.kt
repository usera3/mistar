package cn.qingliao.app

import android.content.Context

/** Logical dp, not physical pixels: covers the 410x502 watch at multiple system densities. */
internal object CompactUi {
    fun enabled(context:Context)=context.resources.configuration.let{it.screenWidthDp<=320||it.screenWidthDp<=450&&it.screenHeightDp<=600}
    fun tiny(context:Context)=context.resources.configuration.let{it.screenWidthDp<=240||it.screenHeightDp<=320}
}
