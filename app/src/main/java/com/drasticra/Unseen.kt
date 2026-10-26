package com.drasticra

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class UnseenItem(val title: String, val desc: String, val points: Int, val badge: String, val game: String)

/** Achievements unlocked while you were playing, waiting to be celebrated when you open the app. */
object Unseen {
    @Synchronized
    fun add(ctx: Context, item: UnseenItem) {
        val sp = ctx.prefs()
        val arr = try {
            JSONArray(sp.getString("unseen", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val o = JSONObject()
        o.put("t", item.title)
        o.put("d", item.desc)
        o.put("p", item.points)
        o.put("b", item.badge)
        o.put("g", item.game)
        arr.put(o)
        val out = JSONArray()
        for (i in maxOf(0, arr.length() - 60) until arr.length()) out.put(arr.get(i))
        sp.edit().putString("unseen", out.toString()).commit()
    }

    @Synchronized
    fun take(ctx: Context): List<UnseenItem> {
        val sp = ctx.prefs()
        val res = ArrayList<UnseenItem>()
        try {
            val arr = JSONArray(sp.getString("unseen", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                res.add(UnseenItem(o.optString("t"), o.optString("d"), o.optInt("p"), o.optString("b"), o.optString("g")))
            }
        } catch (_: Exception) {
        }
        sp.edit().remove("unseen").commit()
        return res
    }
}
