[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-True {
    param(
        [Parameter(Mandatory = $true)]
        [bool]$Condition,
        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Assert-ExitCode {
    param(
        [Parameter(Mandatory = $true)]
        [PSCustomObject]$Result,
        [Parameter(Mandatory = $true)]
        [bool]$ShouldSucceed
    )

    $succeeded = $Result.ExitCode -eq 0
    if ($succeeded -ne $ShouldSucceed) {
        throw "Unexpected exit code $($Result.ExitCode). Output: $($Result.Output)"
    }
}

function Assert-NoTemporaryFiles {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Directory
    )

    if (-not (Test-Path -LiteralPath $Directory -PathType Container)) {
        return
    }
    $temporaryFiles = @(Get-ChildItem -LiteralPath $Directory -Recurse -File -Filter '*.tmp')
    $temporaryPaths = @($temporaryFiles | ForEach-Object { $_.FullName })
    Assert-True ($temporaryFiles.Count -eq 0) "Temporary files remain: $($temporaryPaths -join ', ')"
}

function Invoke-Test {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [Parameter(Mandatory = $true)]
        [scriptblock]$Body
    )

    & $Body
    $script:PassedTests++
    Write-Host "PASS $Name"
}

function Get-Sha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}

function New-BaseModels {
    return @(
        [PSCustomObject]@{
            Prefix = 'image_classifier'
            FileName = 'classifier.tflite'
            Sha256 = Get-Sha256 (Join-Path $script:SourceRoot 'classifier.tflite')
            Url = 'https://example.com/classifier.tflite'
        },
        [PSCustomObject]@{
            Prefix = 'object_detector'
            FileName = 'detector.tflite'
            Sha256 = Get-Sha256 (Join-Path $script:SourceRoot 'detector.tflite')
            Url = 'https://example.com/detector.tflite'
        },
        [PSCustomObject]@{
            Prefix = 'image_embedder'
            FileName = 'embedder.tflite'
            Sha256 = Get-Sha256 (Join-Path $script:SourceRoot 'embedder.tflite')
            Url = 'https://example.com/embedder.tflite'
        }
    )
}

function Write-FixtureManifest {
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$Models,
        [string[]]$ExtraLines = @()
    )

    $lines = foreach ($model in $Models) {
        "$($model.Prefix).file=$($model.FileName)"
        "$($model.Prefix).sha256=$($model.Sha256)"
        "$($model.Prefix).url=$($model.Url)"
    }
    $lines += $ExtraLines
    [System.IO.File]::WriteAllText(
        $script:ManifestPath,
        (($lines -join "`n") + "`n"),
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Invoke-FixtureFetch {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$FetchArguments
    )

    $output = & $script:PowerShellHost -NoProfile -File $script:FixtureFetchScript @FetchArguments 2>&1
    return [PSCustomObject]@{
        ExitCode = $LASTEXITCODE
        Output = ($output | Out-String).Trim()
    }
}

function New-CaseOutput {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    return Join-Path $script:TestRoot "output-$Name"
}

$systemTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd(
    [System.IO.Path]::DirectorySeparatorChar,
    [System.IO.Path]::AltDirectorySeparatorChar
)
$testRoot = Join-Path $systemTempRoot ("frameyu-model-tests-" + [guid]::NewGuid().ToString('N'))
$testFailure = $null
$cleanupFailure = $null

try {
    $script:TestRoot = [System.IO.Path]::GetFullPath($testRoot)
    $fixtureScripts = Join-Path $script:TestRoot 'scripts'
    $fixtureAssets = Join-Path $script:TestRoot 'app/src/main/assets/models'
    $script:SourceRoot = Join-Path $script:TestRoot 'source'
    [System.IO.Directory]::CreateDirectory($fixtureScripts) | Out-Null
    [System.IO.Directory]::CreateDirectory($fixtureAssets) | Out-Null
    [System.IO.Directory]::CreateDirectory($script:SourceRoot) | Out-Null

    $script:FixtureFetchScript = Join-Path $fixtureScripts 'fetch-models.ps1'
    $script:ManifestPath = Join-Path $fixtureAssets 'model-manifest.properties'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'fetch-models.ps1') -Destination $script:FixtureFetchScript

    [System.IO.File]::WriteAllText(
        (Join-Path $script:SourceRoot 'classifier.tflite'),
        'classifier-v1',
        [System.Text.UTF8Encoding]::new($false)
    )
    [System.IO.File]::WriteAllText(
        (Join-Path $script:SourceRoot 'detector.tflite'),
        'detector-v1',
        [System.Text.UTF8Encoding]::new($false)
    )
    [System.IO.File]::WriteAllText(
        (Join-Path $script:SourceRoot 'embedder.tflite'),
        'embedder-v1',
        [System.Text.UTF8Encoding]::new($false)
    )
    [System.IO.File]::WriteAllText(
        (Join-Path $script:SourceRoot 'model.bin'),
        'classifier-v1',
        [System.Text.UTF8Encoding]::new($false)
    )
    [System.IO.File]::WriteAllText(
        (Join-Path $script:SourceRoot 'model-manifest.properties'),
        'classifier-v1',
        [System.Text.UTF8Encoding]::new($false)
    )

    $currentHost = (Get-Process -Id $PID).Path
    if ($PSVersionTable.PSVersion.Major -ge 7) {
        $script:PowerShellHost = $currentHost
    } else {
        $pwshCommand = Get-Command pwsh -ErrorAction Stop
        $script:PowerShellHost = $pwshCommand.Source
    }

    $script:PassedTests = 0

    Invoke-Test 'rejects path traversal file name' {
        $models = New-BaseModels
        $models[0].FileName = '../escape.tflite'
        Write-FixtureManifest $models
        $output = New-CaseOutput 'traversal'
        $result = Invoke-FixtureFetch @('-SourceDirectory', $script:SourceRoot, '-OutputDirectory', $output)
        Assert-ExitCode $result $false
        Assert-NoTemporaryFiles $output
    }

    Invoke-Test 'rejects non-tflite file name' {
        $models = New-BaseModels
        $models[0].FileName = 'model.bin'
        Write-FixtureManifest $models
        $output = New-CaseOutput 'extension'
        $result = Invoke-FixtureFetch @('-SourceDirectory', $script:SourceRoot, '-OutputDirectory', $output)
        Assert-ExitCode $result $false
        Assert-NoTemporaryFiles $output
    }

    Invoke-Test 'rejects duplicate model file names' {
        $models = New-BaseModels
        $models[1].FileName = 'CLASSIFIER.tflite'
        $models[1].Sha256 = $models[0].Sha256
        Write-FixtureManifest $models
        $output = New-CaseOutput 'duplicate-name'
        $result = Invoke-FixtureFetch @('-SourceDirectory', $script:SourceRoot, '-OutputDirectory', $output)
        Assert-ExitCode $result $false
        Assert-NoTemporaryFiles $output
    }

    Invoke-Test 'rejects unknown manifest property' {
        Write-FixtureManifest (New-BaseModels) @('unknown.key=value')
        $output = New-CaseOutput 'unknown-key'
        $result = Invoke-FixtureFetch @('-SourceDirectory', $script:SourceRoot, '-OutputDirectory', $output)
        Assert-ExitCode $result $false
        Assert-NoTemporaryFiles $output
    }

    Invoke-Test 'rejects duplicate manifest property' {
        Write-FixtureManifest (New-BaseModels) @('image_classifier.file=other.tflite')
        $output = New-CaseOutput 'duplicate-key'
        $result = Invoke-FixtureFetch @('-SourceDirectory', $script:SourceRoot, '-OutputDirectory', $output)
        Assert-ExitCode $result $false
        Assert-NoTemporaryFiles $output
    }

    Invoke-Test 'rejects reserved manifest file name' {
        $models = New-BaseModels
        $models[0].FileName = 'model-manifest.properties'
        Write-FixtureManifest $models
        $manifestBefore = Get-Content -LiteralPath $script:ManifestPath -Raw
        $result = Invoke-FixtureFetch @('-SourceDirectory', $script:SourceRoot, '-OutputDirectory', $fixtureAssets)
        Assert-ExitCode $result $false
        Assert-True ((Get-Content -LiteralPath $script:ManifestPath -Raw) -eq $manifestBefore) 'Manifest was overwritten'
        Assert-NoTemporaryFiles $fixtureAssets
    }

    Invoke-Test 'validate-only rejects missing output without creating it' {
        Write-FixtureManifest (New-BaseModels)
        $output = New-CaseOutput 'missing'
        $result = Invoke-FixtureFetch @('-OutputDirectory', $output, '-ValidateOnly')
        Assert-ExitCode $result $false
        Assert-True (-not (Test-Path -LiteralPath $output)) 'ValidateOnly created the output directory'
    }

    Invoke-Test 'validate-only rejects wrong hash without temporary files' {
        $models = New-BaseModels
        Write-FixtureManifest $models
        $output = New-CaseOutput 'wrong-hash'
        [System.IO.Directory]::CreateDirectory($output) | Out-Null
        [System.IO.File]::WriteAllText(
            (Join-Path $output $models[0].FileName),
            'wrong',
            [System.Text.UTF8Encoding]::new($false)
        )
        $result = Invoke-FixtureFetch @('-OutputDirectory', $output, '-ValidateOnly')
        Assert-ExitCode $result $false
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $output $models[1].FileName))) 'ValidateOnly created a missing detector'
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $output $models[2].FileName))) 'ValidateOnly created a missing embedder'
        Assert-NoTemporaryFiles $output
    }

    Invoke-Test 'source hash mismatch preserves existing target and removes temporary files' {
        $models = New-BaseModels
        Write-FixtureManifest $models
        $output = New-CaseOutput 'source-mismatch'
        [System.IO.Directory]::CreateDirectory($output) | Out-Null
        $targetPath = Join-Path $output $models[0].FileName
        [System.IO.File]::WriteAllText(
            $targetPath,
            'existing-target',
            [System.Text.UTF8Encoding]::new($false)
        )
        $targetHashBefore = Get-Sha256 $targetPath
        [System.IO.File]::WriteAllText(
            (Join-Path $script:SourceRoot $models[0].FileName),
            'tampered-source',
            [System.Text.UTF8Encoding]::new($false)
        )
        $result = Invoke-FixtureFetch @('-SourceDirectory', $script:SourceRoot, '-OutputDirectory', $output)
        Assert-ExitCode $result $false
        Assert-True ((Get-Sha256 $targetPath) -eq $targetHashBefore) 'Existing target changed after source hash mismatch'
        Assert-NoTemporaryFiles $output
        [System.IO.File]::WriteAllText(
            (Join-Path $script:SourceRoot $models[0].FileName),
            'classifier-v1',
            [System.Text.UTF8Encoding]::new($false)
        )
    }

    Invoke-Test 'valid local sources replace targets and validate successfully' {
        $models = New-BaseModels
        Write-FixtureManifest $models
        $output = New-CaseOutput 'success'
        [System.IO.Directory]::CreateDirectory($output) | Out-Null
        foreach ($model in $models) {
            [System.IO.File]::WriteAllText(
                (Join-Path $output $model.FileName),
                'stale-target',
                [System.Text.UTF8Encoding]::new($false)
            )
        }

        $installResult = Invoke-FixtureFetch @('-SourceDirectory', $script:SourceRoot, '-OutputDirectory', $output)
        Assert-ExitCode $installResult $true
        foreach ($model in $models) {
            Assert-True ((Get-Sha256 (Join-Path $output $model.FileName)) -eq $model.Sha256) "Installed hash mismatch: $($model.FileName)"
        }
        Assert-NoTemporaryFiles $output

        $validateResult = Invoke-FixtureFetch @('-OutputDirectory', $output, '-ValidateOnly')
        Assert-ExitCode $validateResult $true
        Assert-NoTemporaryFiles $output
    }

    Write-Host "All $script:PassedTests fetch-model tests passed."
} catch {
    $testFailure = $_
} finally {
    try {
        if (Test-Path -LiteralPath $testRoot) {
            $resolvedTestRoot = [System.IO.Path]::GetFullPath($testRoot)
            $resolvedParent = [System.IO.Path]::GetFullPath(
                [System.IO.Directory]::GetParent($resolvedTestRoot).FullName
            ).TrimEnd(
                [System.IO.Path]::DirectorySeparatorChar,
                [System.IO.Path]::AltDirectorySeparatorChar
            )
            if ($resolvedParent -ne $systemTempRoot) {
                throw "Refusing to remove non-temp test directory: $resolvedTestRoot"
            }
            Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
        }
    } catch {
        $cleanupFailure = $_
    }
}

if ($null -ne $cleanupFailure) {
    Write-Error "Cleanup failed: $($cleanupFailure.Exception.Message)"
    exit 1
}
if ($null -ne $testFailure) {
    Write-Error "Test failed: $($testFailure.Exception.Message)"
    exit 1
}
exit 0
