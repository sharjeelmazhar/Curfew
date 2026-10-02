package app.curfew

import androidx.annotation.DrawableRes

/** The logo pictures shipped in the app, by the key [logoKey] gives. Listed by hand rather than
 *  looked up by name, so the release build's resource shrinker keeps every one of them. */
private val LOGOS: Map<String, Int> = mapOf(
    "audacity" to R.drawable.logo_audacity, "blender" to R.drawable.logo_blender,
    "brave" to R.drawable.logo_brave, "chrome" to R.drawable.logo_chrome,
    "chromium" to R.drawable.logo_chromium, "discord" to R.drawable.logo_discord,
    "edge" to R.drawable.logo_edge, "epiphany" to R.drawable.logo_epiphany,
    "firefox" to R.drawable.logo_firefox, "ghostty" to R.drawable.logo_ghostty,
    "gimp" to R.drawable.logo_gimp, "inkscape" to R.drawable.logo_inkscape,
    "krita" to R.drawable.logo_krita, "libreoffice_calc" to R.drawable.logo_libreoffice_calc,
    "libreoffice_impress" to R.drawable.logo_libreoffice_impress, "libreoffice_writer" to R.drawable.logo_libreoffice_writer,
    "minecraft" to R.drawable.logo_minecraft, "obs" to R.drawable.logo_obs,
    "opera" to R.drawable.logo_opera, "prism" to R.drawable.logo_prism,
    "signal" to R.drawable.logo_signal, "skype" to R.drawable.logo_skype,
    "slack" to R.drawable.logo_slack, "sober" to R.drawable.logo_sober,
    "spotify" to R.drawable.logo_spotify, "steam" to R.drawable.logo_steam,
    "teams" to R.drawable.logo_teams, "telegram" to R.drawable.logo_telegram,
    "text" to R.drawable.logo_text, "thunderbird" to R.drawable.logo_thunderbird,
    "vivaldi" to R.drawable.logo_vivaldi, "vlc" to R.drawable.logo_vlc,
    "vscode" to R.drawable.logo_vscode, "zoom" to R.drawable.logo_zoom,
)

/** The logo picture for an app or browser name, or null to show the plain icon. */
@DrawableRes
fun logoFor(name: String): Int? = logoKey(name)?.let { LOGOS[it] }
