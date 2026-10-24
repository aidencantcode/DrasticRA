package com.drasticra

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class RaProfile(val name: String, val pic: String, val softPts: Int, val hardPts: Int)
class RaGame(val id: Int, val title: String, val icon: String, val console: String,
             val achieved: Int, val total: Int, val badges: List<String>)
class RaAch(val title: String, val desc: String, val points: Int, val badge: String, val game: String, val date: String)
class RaHome(val profile: RaProfile, val games: List<RaGame>, val earned: List<RaAch>)

object Ra {
    fun fetch(user: String, key: String): String {
        val u = URLEncoder.encode(user, "UTF-8")
        val k = URLEncoder.encode(key, "UTF-8")
        val url = "https://retroachievements.org/API/API_GetUserSummary.php?z=$u&y=$k&u=$u&g=30&a=50"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "DrasticRA/0.3.0")
        val code = c.responseCode
        val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.readText() ?: ""
        if (code >= 400) throw IllegalStateException("HTTP $code - check username and Web API key")
        return text
    }

    fun parse(text: String, user: String): RaHome {
        val j = JSONObject(text)
        val name = j.optString("User", user).ifEmpty { user }
        val pic = j.optString("UserPic", "/UserPic/$name.png")
        val hard = j.optInt("TotalPoints", j.optInt("Points", 0))
        val soft = j.optInt("TotalSoftcorePoints", j.optInt("SoftcorePoints", 0))

        // achievements (object keyed by game id -> object keyed by achievement id)
        val earnedAll = ArrayList<RaAch>()
        val gameOf = HashMap<Int, ArrayList<RaAch>>()
        val ra = j.optJSONObject("RecentAchievements")
        if (ra != null) {
            val gids = ra.keys()
            while (gids.hasNext()) {
                val gid = gids.next()
                val inner = ra.optJSONObject(gid) ?: continue
                val aids = inner.keys()
                while (aids.hasNext()) {
                    val a = inner.optJSONObject(aids.next()) ?: continue
                    val item = RaAch(
                        a.optString("Title"), a.optString("Description"), a.optInt("Points"),
                        a.optString("BadgeName"), a.optString("GameTitle"), a.optString("DateAwarded")
                    )
                    earnedAll.add(item)
                    gameOf.getOrPut(gid.toIntOrNull() ?: -1) { ArrayList() }.add(item)
                }
            }
        }
        earnedAll.sortByDescending { it.date }

        val awarded = j.optJSONObject("Awarded")
        val all = ArrayList<RaGame>()
        val ds = ArrayList<RaGame>()
        val rp = j.optJSONArray("RecentlyPlayed")
        if (rp != null) {
            for (i in 0 until rp.length()) {
                val g = rp.optJSONObject(i) ?: continue
                val id = g.optInt("GameID")
                val aw = awarded?.optJSONObject(id.toString())
                val total = aw?.optInt("NumPossibleAchievements", g.optInt("AchievementsTotal")) ?: g.optInt("AchievementsTotal")
                val got = aw?.optInt("NumAchieved", 0) ?: 0
                val badges = (gameOf[id] ?: ArrayList<RaAch>()).sortedByDescending { it.date }.take(6).map { it.badge }
                val console = g.optString("ConsoleName")
                val game = RaGame(id, g.optString("Title"), g.optString("ImageIcon"), console, got, total, badges)
                all.add(game)
                val cid = g.optInt("ConsoleID")
                if (cid == 18 || cid == 78 || console.contains("DS")) ds.add(game)
            }
        }
        val games = (if (ds.isEmpty()) all else ds).take(6)
        val ids = games.map { it.id }.toSet()
        val earned = earnedAll.filter { a ->
            var ok = ids.isEmpty()
            for (gid in ids) {
                if (gameOf[gid]?.contains(a) == true) { ok = true; break }
            }
            ok
        }.take(12)
        return RaHome(RaProfile(name, pic, soft, hard), games, earned)
    }
}
