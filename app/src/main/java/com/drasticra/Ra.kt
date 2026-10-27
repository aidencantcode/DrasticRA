package com.drasticra

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class RaProfile(val name: String, val pic: String, val softPts: Int, val hardPts: Int)
class RaGame(
    val id: Int, val title: String, val icon: String, val console: String,
    val achieved: Int, val total: Int, val badges: List<String>
)
class RaAch(
    val title: String, val desc: String, val points: Int, val badge: String,
    val game: String, val gameIcon: String, val console: String, val date: String, val gameId: Int
)
class RaAchDetail(
    val id: Int, val title: String, val desc: String, val points: Int, val badge: String,
    val earned: Boolean, val date: String, val order: Int
)
class RaGameDetail(
    val id: Int, val title: String, val icon: String, val console: String,
    val total: Int, val got: Int, val list: List<RaAchDetail>
)
class RaHome(val profile: RaProfile, val recent: List<RaGame>, val earned: List<RaAch>)
class RaLogin(val ok: Boolean, val user: String, val token: String, val error: String)

object Ra {
    private const val UA = "DrasticRA/0.8.0"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.readText() ?: ""
        if (code >= 400) throw IllegalStateException("HTTP $code - check username and Web API key")
        return text
    }

    private fun api(endpoint: String, user: String, key: String, extra: String): String {
        val url = "https://retroachievements.org/API/$endpoint?z=${enc(user)}&y=${enc(key)}&u=${enc(user)}$extra"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 40000
        c.setRequestProperty("User-Agent", UA)
        return read(c)
    }

    fun summary(user: String, key: String): String =
        api("API_GetUserSummary.php", user, key, "&g=50&a=50")

    fun earnedAllText(user: String, key: String): String =
        api("API_GetAchievementsEarnedBetween.php", user, key, "&f=0&t=" + (System.currentTimeMillis() / 1000L))

    fun completionText(user: String, key: String): String =
        api("API_GetUserCompletionProgress.php", user, key, "&c=500&o=0")

    /** Password login (same call emulators use). Returns a token we keep instead of the password. */
    fun login(user: String, pass: String): RaLogin {
        return try {
            val c = URL("https://retroachievements.org/dorequest.php").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(("r=login2&u=" + enc(user) + "&p=" + enc(pass)).toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.readText() ?: ""
            val j = JSONObject(text)
            if (j.optBoolean("Success")) {
                RaLogin(true, j.optString("User", user).ifEmpty { user }, j.optString("Token"), "")
            } else {
                RaLogin(false, user, "", j.optString("Error", "Login failed"))
            }
        } catch (e: Exception) {
            RaLogin(false, user, "", "Could not log in: " + (e.message ?: "network error"))
        }
    }

    fun gameText(user: String, key: String, gameId: Int): String =
        api("API_GetGameInfoAndUserProgress.php", user, key, "&g=$gameId&a=1")

    /** set by the app: where game pages are kept for offline viewing */
    @Volatile var cacheDir: java.io.File? = null

    /** [gameText] that remembers the answer and falls back to the last copy when there is no internet. */
    fun gameTextCached(user: String, key: String, gameId: Int): String {
        val f = cacheDir?.let { java.io.File(it, "game_$gameId.json") }
        return try {
            val t = gameText(user, key, gameId)
            try {
                f?.writeText(t)
            } catch (_: Exception) {
            }
            t
        } catch (e: Exception) {
            if (f != null && f.exists()) f.readText() else throw e
        }
    }

    private fun JSONObject.str(k: String): String = if (has(k) && !isNull(k)) optString(k) else ""

    fun parseGame(text: String): RaGameDetail {
        val j = JSONObject(text)
        val list = ArrayList<RaAchDetail>()
        val ach = j.optJSONObject("Achievements")
        if (ach != null) {
            val keys = ach.keys()
            while (keys.hasNext()) {
                val o = ach.optJSONObject(keys.next()) ?: continue
                val d1 = o.str("DateEarned")
                val d2 = o.str("DateEarnedHardcore")
                val date = if (d1.isNotEmpty()) d1 else d2
                list.add(
                    RaAchDetail(
                        o.optInt("ID"), o.str("Title"), o.str("Description"), o.optInt("Points"),
                        o.str("BadgeName"), date.isNotEmpty(), date, o.optInt("DisplayOrder")
                    )
                )
            }
        }
        list.sortWith(compareBy<RaAchDetail>({ it.order }, { it.id }))
        val got = list.count { it.earned }
        return RaGameDetail(j.optInt("ID"), j.str("Title"), j.str("ImageIcon"), j.str("ConsoleName"), list.size, got, list)
    }

    /** Current total points (softcore + hardcore) using the saved login token. */
    fun score(user: String, token: String): Int? {
        return try {
            val c = URL("https://retroachievements.org/dorequest.php").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(("r=login2&u=" + enc(user) + "&t=" + enc(token)).toByteArray(Charsets.UTF_8)) }
            val text = (if (c.responseCode >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.readText() ?: ""
            val j = JSONObject(text)
            if (j.optBoolean("Success")) j.optInt("Score") + j.optInt("SoftcoreScore") else null
        } catch (e: Exception) {
            null
        }
    }

    fun parseSummary(text: String, user: String): RaHome {
        val j = JSONObject(text)
        val name = j.optString("User", user).ifEmpty { user }
        val pic = j.optString("UserPic", "/UserPic/$name.png")
        val hard = j.optInt("TotalPoints", j.optInt("Points", 0))
        val soft = j.optInt("TotalSoftcorePoints", j.optInt("SoftcorePoints", 0))

        val earnedAll = ArrayList<RaAch>()
        val byGame = HashMap<Int, ArrayList<RaAch>>()
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
                        a.optString("BadgeName"), a.optString("GameTitle"), "", "", a.optString("DateAwarded"),
                        gid.toIntOrNull() ?: 0
                    )
                    earnedAll.add(item)
                    byGame.getOrPut(gid.toIntOrNull() ?: -1) { ArrayList() }.add(item)
                }
            }
        }
        earnedAll.sortByDescending { it.date }

        val awarded = j.optJSONObject("Awarded")
        val games = ArrayList<RaGame>()
        val rp = j.optJSONArray("RecentlyPlayed")
        if (rp != null) {
            for (i in 0 until rp.length()) {
                val g = rp.optJSONObject(i) ?: continue
                val id = g.optInt("GameID")
                val aw = awarded?.optJSONObject(id.toString())
                val total = aw?.optInt("NumPossibleAchievements", g.optInt("AchievementsTotal")) ?: g.optInt("AchievementsTotal")
                val got = aw?.optInt("NumAchieved", 0) ?: 0
                val badges = (byGame[id] ?: ArrayList<RaAch>()).sortedByDescending { it.date }.take(6).map { it.badge }
                games.add(RaGame(id, g.optString("Title"), g.optString("ImageIcon"), g.optString("ConsoleName"), got, total, badges))
            }
        }
        return RaHome(RaProfile(name, pic, soft, hard), games, earnedAll.take(30))
    }

    fun parseEarned(text: String): List<RaAch> {
        val arr = JSONArray(text)
        val out = ArrayList<RaAch>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                RaAch(
                    o.optString("Title"), o.optString("Description"), o.optInt("Points"), o.optString("BadgeName"),
                    o.optString("GameTitle"), o.optString("GameIcon"), o.optString("ConsoleName"), o.optString("Date"),
                    o.optInt("GameID")
                )
            )
        }
        out.sortByDescending { it.date }
        val seen = HashSet<String>()
        val dedup = ArrayList<RaAch>()
        for (a in out) {
            if (seen.add(a.game + "|" + a.title)) dedup.add(a)
            if (dedup.size >= 250) break
        }
        return dedup
    }

    fun parseCompletion(text: String): List<RaGame> {
        val j = JSONObject(text)
        val arr = j.optJSONArray("Results") ?: return ArrayList()
        val out = ArrayList<RaGame>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                RaGame(
                    o.optInt("GameID"), o.optString("Title"), o.optString("ImageIcon"), o.optString("ConsoleName"),
                    o.optInt("NumAwarded"), o.optInt("MaxPossible"), ArrayList<String>()
                )
            )
        }
        out.sortWith(compareByDescending<RaGame> { if (it.total > 0) it.achieved * 1000 / it.total else 0 }.thenBy { it.title })
        return out
    }
}
