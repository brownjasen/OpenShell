param([string]$OutputPath = (Join-Path $PSScriptRoot '../packaging/OpenShell.ico'))
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$frames = foreach ($size in @(16, 24, 32, 48, 64, 128, 256)) {
    $bitmap = [Drawing.Bitmap]::new($size, $size)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $background = [Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(36, 105, 180))
    $pen = [Drawing.Pen]::new([Drawing.Color]::White, [single]($size * 0.085))
    $stream = [IO.MemoryStream]::new()
    try {
        $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $graphics.Clear([Drawing.Color]::Transparent)
        $graphics.FillRectangle($background, 0, 0, $size, $size)
        $graphics.DrawLine($pen, [single]($size*.24), [single]($size*.31), [single]($size*.43), [single]($size*.49))
        $graphics.DrawLine($pen, [single]($size*.43), [single]($size*.49), [single]($size*.24), [single]($size*.68))
        $graphics.DrawLine($pen, [single]($size*.50), [single]($size*.68), [single]($size*.77), [single]($size*.68))
        $bitmap.Save($stream, [Drawing.Imaging.ImageFormat]::Png)
        [pscustomobject]@{ Size=$size; Bytes=$stream.ToArray() }
    } finally { $graphics.Dispose(); $bitmap.Dispose(); $background.Dispose(); $pen.Dispose(); $stream.Dispose() }
}
$path = [IO.Path]::GetFullPath($OutputPath)
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path)) | Out-Null
$writer = [IO.BinaryWriter]::new([IO.File]::Create($path))
try {
    $writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]$frames.Count)
    $offset = 6 + 16*$frames.Count
    foreach ($frame in $frames) {
        $dimension = if ($frame.Size -eq 256) { 0 } else { $frame.Size }
        $writer.Write([byte]$dimension); $writer.Write([byte]$dimension)
        $writer.Write([byte]0); $writer.Write([byte]0); $writer.Write([uint16]1); $writer.Write([uint16]32)
        $writer.Write([uint32]$frame.Bytes.Length); $writer.Write([uint32]$offset)
        $offset += $frame.Bytes.Length
    }
    foreach ($frame in $frames) { $writer.Write([byte[]]$frame.Bytes) }
} finally { $writer.Dispose() }
Write-Output "Created $path"
