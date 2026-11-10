# Publish pre-check: verify that the DEX still keeps the JSON field names Gson needs.
#
# Why: R8 renames Java fields to a/b/c, and Gson uses the *field name* as the JSON key
# by default. Once renamed, the server fails to deserialize the body and returns an
# empty-body 400 (login/register break instantly), which is very hard to trace.
#
# NOTE: intentionally ASCII-only. Windows PowerShell 5.1 parses .ps1 as ANSI unless it
# has a UTF-8 BOM, so non-ASCII text here would be garbled into syntax errors.
#
# Usage: powershell -File publish-check-dex.ps1 <apk-path>
param([Parameter(Mandatory=$true)][string]$ApkPath)

$ErrorActionPreference = 'Stop'

# These must survive minification, or the API contract / saved-account format is broken.
$required = @('username','password','old_password','new_password','friend_id',
              'duration_sec','avatar_color','nickname','userId','e2eePriv','avatarColor','wxId')

if (-not (Test-Path $ApkPath)) {
    Write-Output "        [FAIL] APK not found: $ApkPath"
    exit 1
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($ApkPath)
$sb = New-Object System.Text.StringBuilder
try {
    foreach ($entry in $zip.Entries) {
        if ($entry.FullName -match '^classes\d*\.dex$') {
            $ms = New-Object System.IO.MemoryStream
            try {
                $entry.Open().CopyTo($ms)
                [void]$sb.Append([System.Text.Encoding]::ASCII.GetString($ms.ToArray()))
            } finally { $ms.Dispose() }
        }
    }
} finally { $zip.Dispose() }

$text = $sb.ToString()
if ($text.Length -eq 0) {
    Write-Output "        [FAIL] no classes*.dex found inside the APK"
    exit 1
}

$missing = @()
foreach ($name in $required) {
    if ($text.IndexOf($name, [System.StringComparison]::Ordinal) -lt 0) { $missing += $name }
}

if ($missing.Count -gt 0) {
    Write-Output ("        [FAIL] obfuscated away: " + ($missing -join ', '))
    Write-Output "               impact: related APIs return empty-body 400, or old saved data cannot be read"
    exit 1
}

Write-Output ("        [OK] all " + $required.Count + " critical JSON field names preserved")
exit 0
