# TV Dashboard setup for Windows: pair a Google TV over Wi-Fi, create settings, install the app
# and switch it on. Needs nothing but Windows PowerShell; it offers to fetch adb.
#
#   irm <address of this script> | iex
#
# Optional, set before running: $env:TVD_APK = 'my-build.apk'; $env:TVD_CONFIG = 'config.json'

& {
    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue' # the progress bar makes downloads very slow
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

    $Release = 'https://github.com/darksn0ww/google-tv-dashboard/releases/latest/download'
    $Pkg = 'com.home.dashboard'
    $Service = "$Pkg/$Pkg.HomeOverrideService"
    $Listener = "$Pkg/$Pkg.MediaListener"
    $base = if ($env:LOCALAPPDATA) { $env:LOCALAPPDATA } else { $HOME }
    $Cache = Join-Path $base 'tv-dashboard'
    $here = (Get-Location).ProviderPath

    function Step($title) { Write-Host "`n== $title" }

    # ---------- Questions ----------

    function Ask($question, $default = '') {
        $prompt = if ($default) { "$question [$default]" } else { $question }
        $answer = Read-Host $prompt
        if ($null -eq $answer) { throw 'Stopped.' }
        if ($answer.Trim()) { $answer.Trim() } else { "$default" }
    }
    function Secret($question) {
        if ([Console]::IsInputRedirected) { return Ask $question }
        $secure = Read-Host "$question (hidden)" -AsSecureString
        (New-Object System.Net.NetworkCredential('', $secure)).Password.Trim()
    }
    function YesNo($question, $default = 'y') {
        $hint = if ($default -eq 'n') { '(y/N)' } else { '(Y/n)' }
        $answer = Ask "$question $hint"
        if (-not $answer) { $answer = $default }
        $answer -like 'y*'
    }

    # ---------- adb ----------

    function Find-Adb {
        $name = if ($env:OS -eq 'Windows_NT') { 'adb.exe' } else { 'adb' }
        $candidates = @($env:ADB, (Get-Command adb -ErrorAction SilentlyContinue).Source)
        if ($env:ANDROID_HOME) { $candidates += Join-Path $env:ANDROID_HOME "platform-tools/$name" }
        $candidates += Join-Path $Cache "platform-tools/$name"
        foreach ($c in $candidates) { if ($c -and (Test-Path $c -PathType Leaf)) { return $c } }
        if ($env:OS -ne 'Windows_NT') { throw 'adb was not found. On macOS or Linux use setup.sh.' }
        Write-Host "adb (Google's Android tool) isn't installed."
        Write-Host "It can be downloaded from Google into $Cache (licence: https://developer.android.com/studio/terms)."
        if (-not (YesNo 'Download it?')) { throw 'Install adb, then run this again.' }
        New-Item -ItemType Directory -Force $Cache | Out-Null
        $zip = Join-Path $Cache 'platform-tools.zip'
        Invoke-WebRequest -UseBasicParsing 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
        Expand-Archive $zip $Cache -Force
        Remove-Item $zip
        Join-Path $Cache "platform-tools/$name"
    }

    # adb writes ordinary messages to stderr, which must not count as a PowerShell error.
    function Adb {
        $ErrorActionPreference = 'Continue'
        (& $script:AdbPath @args 2>&1 | ForEach-Object { "$_" }) -join "`n"
    }
    function Tv { Adb -s $script:Serial shell @args }

    function Ask-Port($question, $default = '') {
        while ($true) {
            $p = Ask $question $default
            if ($p -match '^\d{1,5}$') { return $p }
            Write-Host '  A port is a number, e.g. 37123.'
        }
    }

    function Connect-Tv {
        Step 'Turn on Wireless debugging on the TV'
        Write-Host @'
  1. Settings > System > About: select "Android TV OS build" seven times.
     (On some TVs it's Settings > Device Preferences > About > Build.)
  2. Back in System, open Developer options and turn on "Wireless debugging"
     (some TVs call it "USB debugging" or "Network debugging").
  3. The TV and this computer must be on the same network.
'@
        while ($true) {
            $ip = Ask "`nTV IP address"
            if ($ip -match '^[A-Za-z0-9.-]+$') { break }
            Write-Host "  That doesn't look like an IP address."
        }
        if (YesNo 'Does this computer still need pairing with the TV?') {
            Write-Host '  On the TV, choose "Pair device with pairing code". Keep that box open.'
            while ($true) {
                $port = Ask-Port '  Port shown in the pairing box'
                $code = Ask '  Six-digit pairing code'
                $result = Adb pair "${ip}:$port" $code
                if ($result -match 'Successfully paired') { Write-Host '  Paired.'; break }
                Write-Host "  Pairing failed: $result"
                if (-not (YesNo '  Try again?')) { throw 'Not paired.' }
            }
            Write-Host "`n  Close the box. The Wireless debugging screen shows a DIFFERENT port for connecting."
        }
        while ($true) {
            $port = Ask-Port 'Connection port' '5555'
            $script:Serial = "${ip}:$port"
            $result = Adb connect $script:Serial
            foreach ($i in 1..10) {
                $state = (Adb -s $script:Serial get-state).Trim()
                if ($state -eq 'device') { Write-Host "  Connected to $(Tv getprop ro.product.model)."; return }
                if ($state -match 'unauthorized') { Write-Host '  Choose "Allow" on the TV...' }
                Start-Sleep 2
            }
            Write-Host "  Couldn't connect: $result"
            if (-not (YesNo '  Try another port?')) { throw 'Not connected.' }
        }
    }

    # ---------- Backup ----------

    # Saves how the TV (and this folder) were before anything changes, with commands to put it back.
    function Backup-Tv($settingsFile) {
        Step 'Backing up'
        $dir = Join-Path $here ('tv-dashboard-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
        New-Item -ItemType Directory $dir | Out-Null
        $restore = New-Object System.Collections.Generic.List[string]
        $restore.Add("# To put the TV back, connect with: adb connect $script:Serial")
        $restore.Add('# then run the lines below. The first removes the dashboard, its settings and sign-ins.')
        $restore.Add("adb uninstall $Pkg")
        foreach ($item in 'secure:enabled_accessibility_services', 'secure:accessibility_enabled',
            'secure:enabled_notification_listeners', 'secure:screensaver_enabled', 'system:screen_off_timeout') {
            $ns, $name = $item.Split(':')
            $value = (Tv settings get $ns $name).Trim()
            if ($value -eq 'null') { $restore.Add("adb shell settings delete $ns $name") }
            elseif ($value) { $restore.Add("adb shell `"settings put $ns $name '$value'`"") }
        }
        [IO.File]::WriteAllText((Join-Path $dir 'packages.txt'), (Tv pm list packages))
        $path = @((Tv pm path $Pkg) -split "`n" | Where-Object { $_ -match '^package:' })
        if ($path.Count) {
            $old = Join-Path $dir 'previous-app.apk'
            Adb -s $script:Serial pull ($path[0] -replace '^package:', '').Trim() $old | Out-Null
            if (-not (Test-Path $old)) { throw "Couldn't back up the installed app; nothing was changed." }
            $restore.Add('# The version you had is previous-app.apk: after uninstalling, adb install previous-app.apk')
        }
        [IO.File]::WriteAllLines((Join-Path $dir 'restore.txt'), $restore)
        foreach ($f in (Join-Path $here 'config.json'), (Join-Path $here 'wallpaper.img'), $settingsFile) {
            if ($f -and (Test-Path -LiteralPath $f)) { Copy-Item -LiteralPath $f -Destination $dir -Force }
        }
        Write-Host "  Saved to $dir`n  It can hold private links and keys: keep it to yourself."
    }

    # ---------- App ----------

    function Get-Apk($folder) {
        Step 'Downloading the latest release'
        $apk = Join-Path $folder 'tv-dashboard.apk'
        $sums = Join-Path $folder 'SHA256SUMS.txt'
        Invoke-WebRequest -UseBasicParsing "$Release/tv-dashboard.apk" -OutFile $apk
        Invoke-WebRequest -UseBasicParsing "$Release/SHA256SUMS.txt" -OutFile $sums
        $hash = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
        if (-not (Select-String -Path $sums -Pattern "^$hash .*tv-dashboard\.apk" -Quiet)) {
            throw "The download doesn't match its published checksum; nothing was installed."
        }
        Write-Host '  Checksum OK.'
        $apk
    }

    # ---------- Settings ----------

    function Set-Location-Settings($config) {
        Write-Host "`nWeather location`n  1. A town you choose`n  2. Automatic, from your internet connection (the TV's public IP address goes to geojs.io)"
        $mode = Ask 'Which?' '1'
        while ($true) {
            $town = if ($mode -ne '2') { Ask '  Town (blank for automatic)' } else { '' }
            if (-not $town) { $config.location = 'auto'; return }
            $reply = Invoke-RestMethod "https://geocoding-api.open-meteo.com/v1/search?count=5&name=$([uri]::EscapeDataString($town))"
            $found = @($reply.results | Where-Object { $_ })
            if (-not $found.Count) { Write-Host '  Nothing found; try a nearby town.'; continue }
            for ($i = 0; $i -lt $found.Count; $i++) { Write-Host "  $($i + 1). $($found[$i].name), $($found[$i].admin1), $($found[$i].country)" }
            $choice = (Ask '  Which one?' '1') -as [int]
            if ($choice -ge 1 -and $choice -le $found.Count) {
                $p = $found[$choice - 1]
                $config.place = $p.name; $config.latitude = $p.latitude; $config.longitude = $p.longitude
                return
            }
        }
    }

    function Set-Wallpaper($config, $folder) {
        Write-Host "`nWallpaper`n  1. Random photos from Unsplash (needs a free access key)`n  2. Your own picture: a file on this computer, or a web link`n  3. Plain background"
        switch (Ask 'Which?' '1') {
            '1' { $v = Secret '  Unsplash access key (blank for a plain background)'; if ($v) { $config.unsplashKey = $v } }
            '2' {
                while ($true) {
                    $v = (Ask '  File path or https:// link (blank to skip)').Trim([char[]]"`"' ")
                    if (-not $v) { return }
                    if ($v -match '^https?://') { $config.wallpaper = $v; return }
                    if ($v -notmatch '\.(jpe?g|png|webp)$') { Write-Host '  Use a .jpg, .png or .webp picture.'; continue }
                    if (Test-Path -LiteralPath $v -PathType Leaf) {
                        Copy-Item -LiteralPath $v -Destination (Join-Path $folder 'wallpaper.img') -Force
                        $config.wallpaper = 'file'; return
                    }
                    Write-Host "  Can't find that file."
                }
            }
        }
    }

    # Numbered menu of entity IDs; returns the chosen ones.
    function Pick($title, $ids, [switch]$Several) {
        $ids = @($ids | Where-Object { $_ })
        if (-not $ids.Count) { return }
        Write-Host "`n  $title"
        for ($i = 0; $i -lt $ids.Count; $i++) { Write-Host ('   {0,2}. {1}' -f ($i + 1), $ids[$i]) }
        $question = if ($Several) { '  Numbers separated by commas, or blank for none' } else { '  Number, or blank for none' }
        $numbers = [regex]::Matches((Ask $question), '\d+') | ForEach-Object { [int]$_.Value }
        $chosen = @($numbers | Where-Object { $_ -ge 1 -and $_ -le $ids.Count } | ForEach-Object { $ids[$_ - 1] })
        if ($Several) { $chosen } else { $chosen | Select-Object -First 1 }
    }

    function Set-HomeAssistant($config) {
        Write-Host "`n  In Home Assistant, make a user for the TV with Administrator off, sign in as it,`n  and create a token under Security > Long-lived access tokens."
        while ($true) {
            $url = (Ask '  Home Assistant address, e.g. http://192.168.1.10:8123').TrimEnd('/')
            $token = Secret '  Long-lived access token'
            try { $states = Invoke-RestMethod "$url/api/states" -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 10; break }
            catch { Write-Host "  Couldn't read Home Assistant. Check the address and token." }
            if (-not (YesNo '  Try again?')) { return }
        }
        $all = @($states | ForEach-Object { $_.entity_id } | Sort-Object)
        function Of($domains, $like = '.') { $all | Where-Object { $_ -match "^($domains)\." -and $_ -match $like } }

        $h = [ordered]@{ url = $url; token = $token }
        $v = Pick 'Thermostat for the heating card' (Of 'climate')
        if ($v) {
            $h.climate = $v
            $v = Pick 'Room temperature sensor (optional)' (Of 'sensor' 'temperature'); if ($v) { $h.temperature = $v }
            $v = Pick 'Humidity sensor (optional)' (Of 'sensor' 'humidity'); if ($v) { $h.humidity = $v }
        }
        $v = Pick 'Robot vacuum for the smart home panel' (Of 'vacuum')
        if ($v) {
            $h.vacuum = $v
            $v = Pick 'Its battery sensor (optional)' (Of 'sensor' 'battery'); if ($v) { $h.battery = $v }
            $maps = @(Pick 'Vacuum maps to show, one per floor (optional)' (Of 'image') -Several)
            if ($maps.Count) {
                $h.maps = $maps
                $v = Pick 'Which one names the floor in use? (optional)' (Of 'select' 'selected_map'); if ($v) { $h.mapSelect = $v }
            }
        }
        $devices = @(foreach ($d in @(Pick 'Plugs and lights the TV may switch' (Of 'light|switch|fan|input_boolean') -Several)) {
            [ordered]@{ name = (Ask "  Name for $d" ($d.Split('.')[1] -replace '_', ' ')); entity = $d }
        })
        if ($devices.Count) { $h.devices = $devices }
        $config.homeAssistant = $h
    }

    function Set-Nest($config) {
        Write-Host "`n  Nest is experimental. You need your own Google Device Access project (see the README)."
        $project = Ask '  Device Access project ID'
        $client = Ask '  OAuth client ID'
        $key = Secret '  OAuth client secret'
        $query = "redirect_uri=https%3A%2F%2Fwww.google.com&access_type=offline&prompt=consent&client_id=$([uri]::EscapeDataString($client))&response_type=code&scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fsdm.service"
        Write-Host "`n  Open this, choose your home and thermostat, and allow access:`n`n  https://nestservices.google.com/partnerconnections/$project/auth?$query`n"
        $pasted = Ask "  Paste the address of the google.com page you end up on"
        if ($pasted -notmatch '[?&]code=([^&]+)') { Write-Host '  No code in that address; Nest not added.'; return }
        $form = @{ client_id = $client; client_secret = $key; code = [uri]::UnescapeDataString($Matches[1]); grant_type = 'authorization_code'; redirect_uri = 'https://www.google.com' }
        try { $tokens = Invoke-RestMethod 'https://www.googleapis.com/oauth2/v4/token' -Method Post -Body $form }
        catch { Write-Host '  Google refused the code; Nest not added.'; return }
        if (-not $tokens.refresh_token) { Write-Host '  Google sent no refresh token; Nest not added.'; return }
        # Google only finishes linking after the device list has been requested once.
        try { Invoke-RestMethod "https://smartdevicemanagement.googleapis.com/v1/enterprises/$project/devices" -Headers @{ Authorization = "Bearer $($tokens.access_token)" } | Out-Null } catch { }
        $config.nest = [ordered]@{ projectId = $project; clientId = $client; clientSecret = $key; refreshToken = $tokens.refresh_token }
    }

    function New-Config($path) {
        Step 'Settings (leave anything blank to skip it)'
        $config = [ordered]@{}
        Set-Location-Settings $config
        Set-Wallpaper $config (Split-Path -Parent $path)
        Write-Host "`nCalendar widget`n  1. A plain calendar showing this month`n  2. Upcoming events from your web calendars"
        $calendars = @()
        if ((Ask 'Which?' '1') -eq '2') {
            $calendars = @(while ($v = Ask '  Calendar link, webcal:// or https:// .ics (blank when done)') { $v })
        }
        $config.calendars = $calendars
        $cameras = @(while ($name = Ask 'Camera name (blank when done)') {
            $camera = [ordered]@{ name = $name; url = (Ask '  Low quality RTSP link (for the tile)') }
            $hd = Ask '  High quality link for full screen (optional)'
            if ($hd) { $camera.hd = $hd }
            if ($camera.url) { $camera }
        })
        $config.cameras = $cameras
        Write-Host "`nHeating and smart home`n  1. None`n  2. Through Home Assistant (heating, vacuum, plugs, lights)`n  3. Straight from tado (no server needed)`n  4. Straight from Google Nest (no server needed, experimental)"
        switch (Ask 'Which?' '1') {
            '2' { Set-HomeAssistant $config }
            '3' {
                $config.tado = $true
                $config.tadoZone = Ask '  Room name (blank for the first)'
                Write-Host '  After installing, select the heating card on the TV to sign in.'
            }
            '4' { Set-Nest $config }
        }
        # No byte order mark: Android's JSON reader rejects it.
        [IO.File]::WriteAllText($path, (ConvertTo-Json -InputObject $config -Depth 6), (New-Object System.Text.UTF8Encoding($false)))
        Write-Host "  Saved to $path. It holds private links and keys: don't share it."
    }

    # ---------- Screensaver ----------

    function Set-Screensaver {
        $on = (Tv settings get secure screensaver_enabled).Trim()
        $ms = (Tv settings get system screen_off_timeout).Trim() -as [long]
        $now = if ($on -eq '0') { "never (it's off)" } elseif ($ms) { "after $([math]::Floor($ms / 60000)) minutes" } else { 'after an unknown time' }
        Step 'Screensaver'
        Write-Host "  The TV's ambient screensaver currently starts $now.`n  1. Leave it as it is`n  2. Change how long it waits`n  3. Turn it off (the TV may switch its screen off instead)"
        switch (Ask 'Which?' '1') {
            '2' {
                while ($true) {
                    $minutes = Ask '  Minutes before it starts' '10'
                    if ($minutes -match '^[1-9]\d{0,3}$') { break }
                    Write-Host '  A whole number of minutes, from 1.'
                }
                Tv settings put system screen_off_timeout ([long]$minutes * 60000) | Out-Null
                Tv settings put secure screensaver_enabled 1 | Out-Null
                Write-Host "  Set to $minutes minutes."
            }
            '3' {
                Tv settings put secure screensaver_enabled 0 | Out-Null
                Write-Host '  Off. Run this again and choose 2 to turn it back on.'
            }
        }
    }

    # ---------- Install ----------

    function Start-App { Tv am start -n "$Pkg/.MainActivity" | Out-Null }
    function Test-Bound { (Tv dumpsys accessibility) -match 'label=Home button override' }

    function Send-Config($path) {
        $dest = "/sdcard/Android/data/$Pkg/files"
        Tv mkdir -p $dest | Out-Null
        if ((Get-Content -Raw -LiteralPath $path | ConvertFrom-Json).wallpaper -eq 'file') {
            $picture = Join-Path (Split-Path -Parent $path) 'wallpaper.img'
            if (Test-Path $picture) { Adb -s $script:Serial push $picture "$dest/wallpaper" | Out-Null }
            else { Write-Host "  No wallpaper.img next to the settings; keeping what's on the TV." }
        }
        Adb -s $script:Serial push $path "$dest/config.json" | Out-Null
        Tv am force-stop $Pkg | Out-Null # the app moves the file into private storage when it starts
        Start-Sleep 1
        Start-App
        Start-Sleep 4
        if ((Tv ls $dest) -match 'config\.json') { Write-Host '  Settings NOT imported; open the app on the TV.' }
        else { Write-Host '  Settings imported.' }
    }

    function Enable-HomeButton { # keeps any other accessibility services you use
        $current = (Tv settings get secure enabled_accessibility_services).Trim()
        $others = @($current.Split(':') | Where-Object { $_ -and $_ -ne 'null' -and $_ -ne $Service })
        foreach ($attempt in 1..5) {
            Start-Sleep 4
            if ($others.Count) { Tv settings put secure enabled_accessibility_services ($others -join ':') | Out-Null }
            else { Tv settings delete secure enabled_accessibility_services | Out-Null }
            Tv settings put secure accessibility_enabled 0 | Out-Null
            Start-Sleep 2
            Tv settings put secure enabled_accessibility_services (($others + $Service) -join ':') | Out-Null
            Tv settings put secure accessibility_enabled 1 | Out-Null
            Start-Sleep 3
            if (Test-Bound) { return $true }
        }
        $false
    }

    # ---------- Run ----------
    # Errors are thrown, never "exit": under "irm | iex" that would close your window.

    $apk = $env:TVD_APK
    $config = $env:TVD_CONFIG
    if ($apk) { $apk = (Resolve-Path $apk).ProviderPath }
    if ($config) { $config = (Resolve-Path $config).ProviderPath }
    $work = $null
    try {
        $script:AdbPath = Find-Adb
        Connect-Tv
        Backup-Tv $config

        if (-not $apk) {
            $work = Join-Path ([IO.Path]::GetTempPath()) ([Guid]::NewGuid())
            New-Item -ItemType Directory $work | Out-Null
            $apk = Get-Apk $work
        }

        if (-not $config) {
            Step 'Settings'
            $local = Join-Path $here 'config.json'
            if ((Test-Path $local) -and (YesNo 'Use the config.json in this folder?')) { $config = $local }
            elseif (YesNo 'Create settings now? (No keeps whatever is already on the TV)') {
                if ((Test-Path $local) -and -not (YesNo 'Overwrite the existing config.json?' 'n')) { throw 'Nothing changed.' }
                New-Config $local
                $config = $local
            }
        }

        Step 'Installing'
        $result = Adb -s $script:Serial install -r $apk
        if ($result -notmatch 'Success') {
            if ($result -match 'UPDATE_INCOMPATIBLE') {
                $result += "`nThe installed copy was signed with a different key. Uninstall it first (this removes its`nsettings and sign-ins): adb uninstall $Pkg"
            }
            throw "Install failed: $result"
        }
        Tv cmd package compile -m speed -f $Pkg | Out-Null
        Start-App # a freshly installed app's services won't bind until it has run once
        Start-Sleep 3
        if ($config) { Send-Config $config }

        Step 'Home button'
        Write-Host "  This switches on an accessibility service so Home opens the dashboard. Android sends it`n  every remote key press; it only acts on Home."
        if (YesNo 'Switch it on?') {
            if (Enable-HomeButton) { Write-Host '  On.' }
            else { Write-Host "  It didn't start. Turn it on in Settings > System > Accessibility." }
        }

        Step 'Continue watching'
        if ((Tv settings get secure enabled_notification_listeners).Contains($Listener)) {
            Tv cmd notification disallow_listener $Listener | Out-Null
            Start-Sleep 1
            Tv cmd notification allow_listener $Listener | Out-Null
            Write-Host '  Already allowed.'
        } elseif (YesNo "Show what you were watching? (needs notification access; notifications aren't read)") {
            Tv cmd notification allow_listener $Listener | Out-Null
        }

        Set-Screensaver

        Start-App
        Write-Host "`nDone. Turn Wireless debugging off on the TV if you don't need it again."
    } finally {
        if ($work) { Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue }
    }
}
