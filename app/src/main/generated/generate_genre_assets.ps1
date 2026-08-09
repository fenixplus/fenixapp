Add-Type -AssemblyName System.Drawing

$ErrorActionPreference = "Stop"
$outDir = Join-Path $PSScriptRoot "..\assets\genre_cards"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Add-Noise([System.Drawing.Graphics]$g, [int]$w, [int]$h, [System.Drawing.Color]$color, [int]$count) {
    $pen = New-Object System.Drawing.Pen($color, 1)
    for ($i = 0; $i -lt $count; $i++) {
        $x = Get-Random -Minimum 0 -Maximum $w
        $y = Get-Random -Minimum 0 -Maximum $h
        $len = Get-Random -Minimum 8 -Maximum 26
        $g.DrawLine($pen, $x, $y, $x + $len, $y + (Get-Random -Minimum -6 -Maximum 6))
    }
    $pen.Dispose()
}

function Draw-Skyline([System.Drawing.Graphics]$g, [int]$w, [int]$h, [System.Drawing.Color]$color) {
    $brush = New-Object System.Drawing.SolidBrush($color)
    $x = 0
    while ($x -lt $w) {
        $bw = Get-Random -Minimum 28 -Maximum 90
        $bh = Get-Random -Minimum 120 -Maximum 330
        $g.FillRectangle($brush, $x, $h - $bh, $bw, $bh)
        $x += $bw - (Get-Random -Minimum 4 -Maximum 18)
    }
    $brush.Dispose()
}

$genres = @(
    @{Slug="action"; A=[System.Drawing.Color]::FromArgb(255,18,24,48); B=[System.Drawing.Color]::FromArgb(255,150,36,24); Accent=[System.Drawing.Color]::FromArgb(220,255,164,62); Draw="action"},
    @{Slug="comedy"; A=[System.Drawing.Color]::FromArgb(255,34,17,52); B=[System.Drawing.Color]::FromArgb(255,212,120,24); Accent=[System.Drawing.Color]::FromArgb(220,255,232,132); Draw="comedy"},
    @{Slug="sci-fi"; A=[System.Drawing.Color]::FromArgb(255,8,18,44); B=[System.Drawing.Color]::FromArgb(255,13,92,120); Accent=[System.Drawing.Color]::FromArgb(220,101,255,249); Draw="scifi"},
    @{Slug="crime"; A=[System.Drawing.Color]::FromArgb(255,11,15,25); B=[System.Drawing.Color]::FromArgb(255,58,70,96); Accent=[System.Drawing.Color]::FromArgb(220,255,214,77); Draw="crime"},
    @{Slug="thriller"; A=[System.Drawing.Color]::FromArgb(255,16,9,18); B=[System.Drawing.Color]::FromArgb(255,119,18,37); Accent=[System.Drawing.Color]::FromArgb(220,255,96,96); Draw="thriller"},
    @{Slug="drama"; A=[System.Drawing.Color]::FromArgb(255,36,16,22); B=[System.Drawing.Color]::FromArgb(255,115,49,61); Accent=[System.Drawing.Color]::FromArgb(220,255,214,168); Draw="drama"},
    @{Slug="horror"; A=[System.Drawing.Color]::FromArgb(255,7,11,14); B=[System.Drawing.Color]::FromArgb(255,49,80,74); Accent=[System.Drawing.Color]::FromArgb(220,203,255,224); Draw="horror"},
    @{Slug="mystery"; A=[System.Drawing.Color]::FromArgb(255,14,20,33); B=[System.Drawing.Color]::FromArgb(255,45,70,95); Accent=[System.Drawing.Color]::FromArgb(220,190,223,255); Draw="mystery"},
    @{Slug="mindfuck"; A=[System.Drawing.Color]::FromArgb(255,18,11,40); B=[System.Drawing.Color]::FromArgb(255,88,22,130); Accent=[System.Drawing.Color]::FromArgb(220,92,255,224); Draw="mindfuck"},
    @{Slug="anime"; A=[System.Drawing.Color]::FromArgb(255,31,15,48); B=[System.Drawing.Color]::FromArgb(255,232,92,82); Accent=[System.Drawing.Color]::FromArgb(220,255,198,124); Draw="anime"},
    @{Slug="documentary"; A=[System.Drawing.Color]::FromArgb(255,13,36,42); B=[System.Drawing.Color]::FromArgb(255,45,118,111); Accent=[System.Drawing.Color]::FromArgb(220,198,255,235); Draw="documentary"},
    @{Slug="romance"; A=[System.Drawing.Color]::FromArgb(255,56,16,36); B=[System.Drawing.Color]::FromArgb(255,201,78,132); Accent=[System.Drawing.Color]::FromArgb(220,255,210,230); Draw="romance"},
    @{Slug="history"; A=[System.Drawing.Color]::FromArgb(255,61,38,21); B=[System.Drawing.Color]::FromArgb(255,153,104,54); Accent=[System.Drawing.Color]::FromArgb(220,243,220,165); Draw="history"},
    @{Slug="animation"; A=[System.Drawing.Color]::FromArgb(255,20,38,67); B=[System.Drawing.Color]::FromArgb(255,71,132,221); Accent=[System.Drawing.Color]::FromArgb(220,255,225,90); Draw="animation"},
    @{Slug="reality_tv"; A=[System.Drawing.Color]::FromArgb(255,45,12,54); B=[System.Drawing.Color]::FromArgb(255,211,70,130); Accent=[System.Drawing.Color]::FromArgb(220,255,226,110); Draw="reality"},
    @{Slug="family"; A=[System.Drawing.Color]::FromArgb(255,24,52,75); B=[System.Drawing.Color]::FromArgb(255,235,141,66); Accent=[System.Drawing.Color]::FromArgb(220,255,240,186); Draw="family"},
    @{Slug="nature"; A=[System.Drawing.Color]::FromArgb(255,10,42,34); B=[System.Drawing.Color]::FromArgb(255,55,127,90); Accent=[System.Drawing.Color]::FromArgb(220,209,255,214); Draw="nature"},
    @{Slug="fantasy"; A=[System.Drawing.Color]::FromArgb(255,22,16,58); B=[System.Drawing.Color]::FromArgb(255,84,49,153); Accent=[System.Drawing.Color]::FromArgb(220,182,202,255); Draw="fantasy"},
    @{Slug="adventure"; A=[System.Drawing.Color]::FromArgb(255,14,53,74); B=[System.Drawing.Color]::FromArgb(255,206,126,49); Accent=[System.Drawing.Color]::FromArgb(220,255,223,138); Draw="adventure"},
    @{Slug="superhero"; A=[System.Drawing.Color]::FromArgb(255,13,20,50); B=[System.Drawing.Color]::FromArgb(255,174,42,54); Accent=[System.Drawing.Color]::FromArgb(220,114,201,255); Draw="superhero"},
    @{Slug="war_military"; A=[System.Drawing.Color]::FromArgb(255,27,36,27); B=[System.Drawing.Color]::FromArgb(255,109,98,62); Accent=[System.Drawing.Color]::FromArgb(220,226,211,152); Draw="war"},
    @{Slug="western"; A=[System.Drawing.Color]::FromArgb(255,72,36,14); B=[System.Drawing.Color]::FromArgb(255,201,113,38); Accent=[System.Drawing.Color]::FromArgb(220,255,212,126); Draw="western"}
)

foreach ($genre in $genres) {
    $w = 1280
    $h = 720
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality

    $bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        [System.Drawing.Rectangle]::FromLTRB(0, 0, $w, $h),
        $genre.A,
        $genre.B,
        35
    )
    $g.FillRectangle($bg, 0, 0, $w, $h)
    $bg.Dispose()

    $glow = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(58, $genre.Accent.R, $genre.Accent.G, $genre.Accent.B))
    $g.FillEllipse($glow, 720, -120, 540, 540)
    $g.FillEllipse($glow, -120, 360, 440, 300)
    $glow.Dispose()

    Add-Noise $g $w $h ([System.Drawing.Color]::FromArgb(18, 255, 255, 255)) 180

    switch ($genre.Draw) {
        "action" {
            $pen = New-Object System.Drawing.Pen($genre.Accent, 18)
            $g.DrawLine($pen, 180, 560, 640, 150)
            $g.DrawLine($pen, 360, 620, 920, 120)
            $pen2 = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(120, 255, 255, 255), 6)
            $g.DrawEllipse($pen2, 820, 120, 180, 180)
            $pen.Dispose()
            $pen2.Dispose()
        }
        "comedy" {
            $spot = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(64, 255, 245, 205))
            $g.FillPie($spot, 760, -80, 420, 500, 0, 180)
            $spot.Dispose()
            foreach ($p in 0..18) {
                $b = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb((Get-Random -Minimum 70 -Maximum 150), (Get-Random -Minimum 210 -Maximum 255), (Get-Random -Minimum 160 -Maximum 255), (Get-Random -Minimum 70 -Maximum 160)))
                $g.FillEllipse($b, (Get-Random -Minimum 160 -Maximum 1050), (Get-Random -Minimum 100 -Maximum 520), (Get-Random -Minimum 12 -Maximum 40), (Get-Random -Minimum 12 -Maximum 40))
                $b.Dispose()
            }
        }
        "scifi" {
            $pen = New-Object System.Drawing.Pen($genre.Accent, 6)
            $g.DrawEllipse($pen, 760, 140, 260, 260)
            $g.DrawEllipse($pen, 720, 180, 340, 180)
            $g.DrawEllipse($pen, 820, 120, 170, 320)
            foreach ($s in 0..80) {
                $b = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb((Get-Random -Minimum 80 -Maximum 180), 255, 255, 255))
                $x = Get-Random -Minimum 40 -Maximum 1220
                $y = Get-Random -Minimum 40 -Maximum 420
                $size = Get-Random -Minimum 2 -Maximum 7
                $g.FillEllipse($b, $x, $y, $size, $size)
                $b.Dispose()
            }
            $pen.Dispose()
        }
        "crime" {
            Draw-Skyline $g $w $h ([System.Drawing.Color]::FromArgb(105, 10, 16, 20))
            $pen = New-Object System.Drawing.Pen($genre.Accent, 18)
            $g.DrawLine($pen, 70, 570, 1150, 410)
            $g.DrawLine($pen, 110, 645, 1190, 485)
            $pen.Dispose()
        }
        "thriller" {
            $red = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(90, 255, 88, 88))
            $g.FillRectangle($red, 880, 0, 180, 720)
            $red.Dispose()
            $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(160, 255, 255, 255), 3)
            foreach ($i in 0..5) { $g.DrawLine($pen, 760 + ($i * 50), 120, 980 + ($i * 30), 620) }
            $pen.Dispose()
        }
        "drama" {
            $curtain = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(70, 255, 255, 255))
            foreach ($i in 0..7) { $g.FillRectangle($curtain, 740 + ($i * 48), 0, 24, 520) }
            $curtain.Dispose()
            $spot = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(60, 255, 235, 190))
            $g.FillPie($spot, 680, -120, 520, 620, 0, 180)
            $spot.Dispose()
        }
        "horror" {
            $moon = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(130, 227, 255, 238))
            $g.FillEllipse($moon, 840, 80, 170, 170)
            $moon.Dispose()
            $fog = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(60, 210, 255, 240))
            foreach ($i in 0..6) { $g.FillEllipse($fog, 180 + ($i * 140), 480 - (($i % 2) * 30), 280, 120) }
            $fog.Dispose()
            $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(150, 18, 25, 23), 8)
            $g.DrawLine($pen, 970, 230, 910, 520)
            $g.DrawLine($pen, 930, 300, 1010, 470)
            $g.DrawLine($pen, 950, 360, 870, 520)
            $pen.Dispose()
        }
        "mystery" {
            $pen = New-Object System.Drawing.Pen($genre.Accent, 14)
            $g.DrawEllipse($pen, 780, 150, 220, 220)
            $g.DrawLine($pen, 940, 310, 1040, 430)
            $pen.Dispose()
            $smoke = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(90, 255, 255, 255), 5)
            foreach ($i in 0..5) { $g.DrawArc($smoke, 500 + ($i * 40), 260 - ($i * 15), 200, 120, 190, 160) }
            $smoke.Dispose()
        }
        "mindfuck" {
            $pen = New-Object System.Drawing.Pen($genre.Accent, 5)
            for ($i = 0; $i -lt 16; $i++) {
                $g.DrawEllipse($pen, 720 - ($i * 18), 150 - ($i * 10), 260 + ($i * 36), 260 + ($i * 20))
            }
            $pen.Dispose()
        }
        "anime" {
            $sun = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(130, 255, 205, 120))
            $g.FillEllipse($sun, 830, 120, 240, 240)
            $sun.Dispose()
            $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(110, 255, 255, 255), 4)
            foreach ($i in 0..12) { $g.DrawLine($pen, 160 + ($i * 38), 620, 520 + ($i * 50), 140) }
            $pen.Dispose()
        }
        "documentary" {
            $pen = New-Object System.Drawing.Pen($genre.Accent, 5)
            $g.DrawEllipse($pen, 760, 130, 280, 280)
            $g.DrawEllipse($pen, 820, 130, 160, 280)
            $g.DrawArc($pen, 760, 170, 280, 200, 0, 180)
            $g.DrawArc($pen, 760, 210, 280, 120, 0, 180)
            $pen.Dispose()
        }
        "romance" {
            $heart = New-Object System.Drawing.Drawing2D.GraphicsPath
            $heart.AddBezier(860, 220, 820, 140, 700, 180, 760, 290)
            $heart.AddBezier(760, 290, 810, 390, 920, 450, 960, 520)
            $heart.AddBezier(960, 520, 1010, 450, 1120, 390, 1160, 290)
            $heart.AddBezier(1160, 290, 1220, 180, 1100, 140, 1060, 220)
            $b = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(80, 255, 218, 230))
            $g.FillPath($b, $heart)
            $b.Dispose()
            $heart.Dispose()
        }
        "history" {
            $brush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(80, 255, 235, 200))
            foreach ($x in 780, 860, 940) { $g.FillRectangle($brush, $x, 180, 36, 300) }
            $g.FillRectangle($brush, 740, 170, 280, 26)
            $g.FillRectangle($brush, 740, 470, 280, 22)
            $brush.Dispose()
        }
        "animation" {
            foreach ($i in 0..12) {
                $b = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb((Get-Random -Minimum 80 -Maximum 170), (Get-Random -Minimum 120 -Maximum 255), (Get-Random -Minimum 120 -Maximum 255), (Get-Random -Minimum 70 -Maximum 255)))
                $pts = [System.Drawing.Point[]]@(
                    [System.Drawing.Point]::new((Get-Random -Minimum 650 -Maximum 1100), (Get-Random -Minimum 120 -Maximum 520)),
                    [System.Drawing.Point]::new((Get-Random -Minimum 700 -Maximum 1180), (Get-Random -Minimum 140 -Maximum 580)),
                    [System.Drawing.Point]::new((Get-Random -Minimum 660 -Maximum 1120), (Get-Random -Minimum 180 -Maximum 620))
                )
                $g.FillPolygon($b, $pts)
                $b.Dispose()
            }
        }
        "reality" {
            $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(130, 255, 255, 255), 8)
            foreach ($i in 0..4) { $g.DrawLine($pen, 760 + ($i * 70), 110, 680 + ($i * 95), 500) }
            $pen.Dispose()
            $b = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(110, 255, 236, 112))
            $g.FillEllipse($b, 860, 170, 130, 130)
            $b.Dispose()
        }
        "family" {
            $b = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(95, 255, 241, 196))
            $house = [System.Drawing.Point[]]@(
                [System.Drawing.Point]::new(810, 480),
                [System.Drawing.Point]::new(930, 340),
                [System.Drawing.Point]::new(1050, 480)
            )
            $g.FillPolygon($b, $house)
            $g.FillRectangle($b, 845, 480, 170, 120)
            $b.Dispose()
        }
        "nature" {
            $mountain = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(100, 230, 255, 238))
            $g.FillPolygon($mountain, [System.Drawing.Point[]]@([System.Drawing.Point]::new(650, 580), [System.Drawing.Point]::new(840, 250), [System.Drawing.Point]::new(1030, 580)))
            $g.FillPolygon($mountain, [System.Drawing.Point[]]@([System.Drawing.Point]::new(820, 590), [System.Drawing.Point]::new(1000, 300), [System.Drawing.Point]::new(1180, 590)))
            $mountain.Dispose()
        }
        "fantasy" {
            $moon = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(120, 220, 229, 255))
            $g.FillEllipse($moon, 900, 70, 160, 160)
            $moon.Dispose()
            $castle = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(95, 255, 255, 255))
            $g.FillRectangle($castle, 860, 280, 160, 240)
            $g.FillRectangle($castle, 820, 350, 40, 170)
            $g.FillRectangle($castle, 1020, 350, 40, 170)
            $castle.Dispose()
        }
        "adventure" {
            $pen = New-Object System.Drawing.Pen($genre.Accent, 5)
            $g.DrawEllipse($pen, 830, 170, 180, 180)
            $g.DrawLine($pen, 920, 170, 920, 350)
            $g.DrawLine($pen, 830, 260, 1010, 260)
            $pen.Dispose()
            $mountain = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(85, 255, 255, 255))
            $g.FillPolygon($mountain, [System.Drawing.Point[]]@([System.Drawing.Point]::new(660, 600), [System.Drawing.Point]::new(840, 360), [System.Drawing.Point]::new(990, 600)))
            $mountain.Dispose()
        }
        "superhero" {
            Draw-Skyline $g $w $h ([System.Drawing.Color]::FromArgb(105, 11, 18, 35))
            $pen = New-Object System.Drawing.Pen($genre.Accent, 10)
            $g.DrawLine($pen, 910, 120, 860, 300)
            $g.DrawLine($pen, 860, 300, 940, 300)
            $g.DrawLine($pen, 940, 300, 900, 490)
            $pen.Dispose()
        }
        "war" {
            $smoke = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(80, 230, 220, 180))
            foreach ($i in 0..4) { $g.FillEllipse($smoke, 700 + ($i * 90), 220 - ($i * 20), 170, 140) }
            $smoke.Dispose()
            $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(140, 30, 36, 22), 8)
            $g.DrawLine($pen, 760, 420, 1110, 250)
            $g.DrawLine($pen, 930, 340, 1030, 420)
            $pen.Dispose()
        }
        "western" {
            $sun = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(110, 255, 212, 126))
            $g.FillEllipse($sun, 880, 120, 220, 220)
            $sun.Dispose()
            $cactus = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(160, 35, 70, 46), 14)
            $g.DrawLine($cactus, 980, 300, 980, 560)
            $g.DrawLine($cactus, 980, 360, 930, 310)
            $g.DrawLine($cactus, 980, 430, 1030, 380)
            $cactus.Dispose()
        }
    }

    $fade = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        [System.Drawing.Rectangle]::FromLTRB(0, 0, $w, $h),
        [System.Drawing.Color]::FromArgb(0, 0, 0, 0),
        [System.Drawing.Color]::FromArgb(200, 0, 0, 0),
        90
    )
    $blend = New-Object System.Drawing.Drawing2D.Blend
    $blend.Factors = [single[]]@(0.0, 0.0, 0.15, 0.7, 1.0)
    $blend.Positions = [single[]]@(0.0, 0.45, 0.68, 0.84, 1.0)
    $fade.Blend = $blend
    $g.FillRectangle($fade, 0, 0, $w, $h)
    $fade.Dispose()

    $codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageDecoders() | Where-Object { $_.MimeType -eq "image/jpeg" }
    $enc = [System.Drawing.Imaging.Encoder]::Quality
    $encParams = New-Object System.Drawing.Imaging.EncoderParameters 1
    $encParams.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter($enc, 90L)
    $outPath = Join-Path $outDir ($genre.Slug + ".jpg")
    $bmp.Save($outPath, $codec, $encParams)
    $g.Dispose()
    $bmp.Dispose()
    $encParams.Dispose()
}
