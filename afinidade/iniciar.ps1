param(
    [ValidateSet('completo','servidor','cliente')][string]$Modo = 'completo',
    [ValidateRange(1,65535)][int]$PortaWeb = 8080,
    [ValidateRange(1,65535)][int]$PortaTcp = 9090,
    [string]$Servidor = '127.0.0.1'
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

if (-not $env:JAVA_HOME) {
    $compiler = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($compiler) { $env:JAVA_HOME = Split-Path (Split-Path $compiler.Source) }
}
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME/bin/javac.exe")) {
    throw 'Configure JAVA_HOME com a pasta de um JDK 17 ou superior. Consulte o README.'
}

# Compila o codigo atual e roda no proprio terminal. Ctrl+C encerra tudo.
& .\mvnw.cmd -q -DskipTests package
if ($LASTEXITCODE -ne 0) { throw 'Falha na compilacao. Confira as mensagens acima.' }

$arguments = @('-jar', 'target/afinidade.jar')
if ($Modo -ne 'completo') { $arguments += $Modo }
$arguments += @("--server.port=$PortaWeb", "--music.tcp.port=$PortaTcp", "--music.server.host=$Servidor")
& "$env:JAVA_HOME/bin/java.exe" @arguments
