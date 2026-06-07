# PostToolUse hook (matcher: Write|Edit) - single-file javac lint + typecheck.
#
# Compiles ONLY the edited .java file against the cached project classpath:
#   - exit 0  -> OK; lint warnings (if any) are returned as additionalContext JSON
#   - exit 2  -> compile error; stderr is fed back into the agent's context
#   - any gate miss (non-.java file, missing target/classes, classpath failure)
#     exits 0 silently so the hook never blocks unrelated work.
#
# Cross-file consistency: -sourcepath lets javac typecheck against current
# sources instead of stale .class files; -implicit:none keeps it from
# compiling anything beyond the edited file.

try { $hook = [Console]::In.ReadToEnd() | ConvertFrom-Json } catch { exit 0 }

$file = $hook.tool_input.file_path
if (-not $file) { $file = $hook.tool_response.filePath }
if (-not $file -or $file -notmatch '\.java$') { exit 0 }
if (-not (Test-Path $file)) { exit 0 }

$root = $env:CLAUDE_PROJECT_DIR
if (-not $root) { $root = Split-Path (Split-Path $PSScriptRoot) }
Set-Location $root

$file = (Resolve-Path $file).Path
if ($file -notmatch '\\src\\(main|test)\\java\\') { exit 0 }

# Default JAVA_HOME on this machine is JDK 11 - must point at JDK 21.
$env:JAVA_HOME = 'C:\Install\jdk-21.0.2\jdk-21.0.2'
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'

# Dependency classpath is cached; regenerate when pom.xml is newer.
$cpFile = Join-Path $PSScriptRoot 'classpath.txt'
$pom = Join-Path $root 'pom.xml'
if (-not (Test-Path $cpFile) -or (Get-Item $pom).LastWriteTime -gt (Get-Item $cpFile).LastWriteTime) {
	& .\mvnw.cmd -o -q dependency:build-classpath "-Dmdep.outputFile=.claude/hooks/classpath.txt" | Out-Null
	if ($LASTEXITCODE -ne 0) {
		# offline miss (new dependency) - retry online
		& .\mvnw.cmd -q dependency:build-classpath "-Dmdep.outputFile=.claude/hooks/classpath.txt" | Out-Null
	}
	if ($LASTEXITCODE -ne 0) { exit 0 }
}
$deps = (Get-Content $cpFile -Raw).Trim()

$mainOut = Join-Path $root 'target\classes'
if (-not (Test-Path $mainOut)) { exit 0 }  # fresh clone: run mvnw compile once first

if ($file -match '\\src\\test\\java\\') {
	$cp = "$deps;$mainOut;" + (Join-Path $root 'target\test-classes')
	$sp = (Join-Path $root 'src\main\java') + ';' + (Join-Path $root 'src\test\java')
} else {
	$cp = "$deps;$mainOut"
	$sp = Join-Path $root 'src\main\java'
}

# Throwaway output dir - never write into Maven's target/.
$outDir = Join-Path $env:TEMP 'garageops-hook-javac'
New-Item -ItemType Directory -Force $outDir | Out-Null

# serial + this-escape are pure noise in Vaadin view code; keep the rest of -Xlint.
$result = & $javac '-J-XX:TieredStopAtLevel=1' -cp $cp -sourcepath $sp `
	-implicit:none -proc:none -d $outDir '-Xlint:all,-serial,-this-escape' $file 2>&1 |
	ForEach-Object { "$_" }
$code = $LASTEXITCODE
$text = ($result | Select-Object -First 60) -join "`n"
if ($text.Length -gt 9000) { $text = $text.Substring(0, 9000) }  # additionalContext cap is 10k

if ($code -ne 0) {
	[Console]::Error.WriteLine("javac: compile errors in $file`n$text")
	exit 2
}
if ($text) {
	@{
		hookSpecificOutput = @{
			hookEventName     = 'PostToolUse'
			additionalContext = "javac lint warnings in $($file):`n$text"
		}
	} | ConvertTo-Json -Compress
}
exit 0
