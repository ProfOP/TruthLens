param(
  [Parameter(Mandatory=$true)][string]$Email,
  [Parameter(Mandatory=$true)][string]$Name,
  [Parameter(Mandatory=$true)][System.Security.SecureString]$Password,
  [Parameter(Mandatory=$true)][string]$JwtSecret
)
$env:TRUTHLENS_BOOTSTRAP_ADMIN_EMAIL=$Email
$env:TRUTHLENS_BOOTSTRAP_ADMIN_NAME=$Name
$env:TRUTHLENS_BOOTSTRAP_ADMIN_PASSWORD=[System.Net.NetworkCredential]::new('', $Password).Password
$env:JWT_SECRET=$JwtSecret
Push-Location (Join-Path $PSScriptRoot '..\backend')
try { & .\mvnw.cmd -q spring-boot:run "-Dspring-boot.run.arguments=--spring.main.web-application-type=none" }
finally { Pop-Location; Remove-Item Env:TRUTHLENS_BOOTSTRAP_ADMIN_EMAIL,Env:TRUTHLENS_BOOTSTRAP_ADMIN_NAME,Env:TRUTHLENS_BOOTSTRAP_ADMIN_PASSWORD -ErrorAction SilentlyContinue }
