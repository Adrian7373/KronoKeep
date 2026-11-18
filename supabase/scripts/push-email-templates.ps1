# Pushes the auth email templates in supabase/templates/ to the hosted project through the
# Management API (the Supabase MCP has no auth-config tool, and the CLI isn't installed).
#
# Usage (PowerShell, repo root). Create a personal access token at
# https://supabase.com/dashboard/account/tokens and keep it out of the repo:
#   $env:SUPABASE_ACCESS_TOKEN = "sbp_..."
#   .\supabase\scripts\push-email-templates.ps1                 # confirmation + recovery
#   .\supabase\scripts\push-email-templates.ps1 -Only confirmation

param(
    [string]$ProjectRef = "djiaejvceiiahbffqnbr",
    [ValidateSet("all", "confirmation", "recovery")]
    [string]$Only = "all"
)

$ErrorActionPreference = "Stop"
if (-not $env:SUPABASE_ACCESS_TOKEN) { throw "Set `$env:SUPABASE_ACCESS_TOKEN first." }

$templates = Join-Path $PSScriptRoot "..\templates"
$body = @{}
if ($Only -in "all", "confirmation") {
    $body.mailer_subjects_confirmation = "Confirm your KronoKeep account"
    $body.mailer_templates_confirmation_content = Get-Content (Join-Path $templates "confirmation.html") -Raw -Encoding utf8
}
if ($Only -in "all", "recovery") {
    $body.mailer_subjects_recovery = "Your KronoKeep password reset code"
    $body.mailer_templates_recovery_content = Get-Content (Join-Path $templates "recovery.html") -Raw -Encoding utf8
}

$json = $body | ConvertTo-Json -Depth 3
$result = Invoke-RestMethod -Method Patch `
    -Uri "https://api.supabase.com/v1/projects/$ProjectRef/config/auth" `
    -Headers @{ Authorization = "Bearer $env:SUPABASE_ACCESS_TOKEN" } `
    -ContentType "application/json; charset=utf-8" `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($json))

"Updated: " + (($body.Keys | Where-Object { $_ -like "mailer_subjects_*" }) -replace "mailer_subjects_", "" -join ", ")
"Confirmation subject now: " + $result.mailer_subjects_confirmation
"Recovery subject now:     " + $result.mailer_subjects_recovery
