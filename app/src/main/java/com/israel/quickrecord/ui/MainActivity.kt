package com.israel.quickrecord.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import com.israel.quickrecord.ButtonState
import com.israel.quickrecord.R

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { QuickRecordTheme { AppRoot() } }
    }

    override fun onResume() {
        super.onResume()
        UiTick.resumed++
    }

    override fun onPause() {
        // The button test must never stay on once the app isn't in front.
        ButtonState.testMode = false
        super.onPause()
    }
}

/** Changes each time the app comes back to the front, so screens re-check permissions and files. */
object UiTick {
    var resumed by mutableIntStateOf(0)
}

enum class Tab(val label: String, val icon: Int) {
    HOME("Record", R.drawable.ic_record),
    RECORDINGS("Recordings", R.drawable.ic_library),
    SETTINGS("Settings", R.drawable.ic_settings),
}

enum class SubScreen { NONE, BUTTON, LOG }

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var sub by rememberSaveable { mutableStateOf(SubScreen.NONE) }

    BackHandler(enabled = sub != SubScreen.NONE) { sub = SubScreen.NONE }
    BackHandler(enabled = sub == SubScreen.NONE && tab != Tab.HOME) { tab = Tab.HOME }

    when (sub) {
        SubScreen.BUTTON -> ButtonScreen(onBack = { sub = SubScreen.NONE })
        SubScreen.LOG -> LogScreen(onBack = { sub = SubScreen.NONE })
        SubScreen.NONE -> Scaffold(
            bottomBar = {
                NavigationBar {
                    for (t in Tab.entries) {
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(painterResource(t.icon), contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        ) { pad: PaddingValues ->
            when (tab) {
                Tab.HOME -> HomeScreen(
                    pad,
                    openButton = { sub = SubScreen.BUTTON },
                    openLog = { sub = SubScreen.LOG },
                    openRecordings = { tab = Tab.RECORDINGS },
                )
                Tab.RECORDINGS -> RecordingsScreen(pad)
                Tab.SETTINGS -> SettingsScreen(
                    pad,
                    openButton = { sub = SubScreen.BUTTON },
                    openLog = { sub = SubScreen.LOG },
                )
            }
        }
    }
}
