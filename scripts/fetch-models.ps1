[CmdletBinding()]
param(
    [string]$SourceDirectory = 'module',
    [string]$OutputDirectory = 'app/src/main/assets/models',
    [switch]$ValidateOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-RepositoryPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$RepositoryRoot
    )

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $RepositoryRoot $Path))
}

function Read-ModelManifest {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ManifestPath
    )

    if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
        throw "Model manifest does not exist: $ManifestPath"
    }

    $properties = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($ManifestPath)) {
        $trimmedLine = $line.Trim()
        if ($trimmedLine.Length -eq 0 -or $trimmedLine.StartsWith('#')) {
            continue
        }

        $separatorIndex = $trimmedLine.IndexOf('=')
        if ($separatorIndex -le 0) {
            throw "Model manifest contains an invalid line: $line"
        }

        $key = $trimmedLine.Substring(0, $separatorIndex).Trim()
        $value = $trimmedLine.Substring($separatorIndex + 1).Trim()
        if ($properties.ContainsKey($key)) {
            throw "Model manifest contains a duplicate property: $key"
        }
        $properties[$key] = $value
    }

    $modelPrefixes = @('image_classifier', 'object_detector', 'image_embedder')
    $requiredKeys = @()
    $models = foreach ($prefix in $modelPrefixes) {
        $fileKey = "$prefix.file"
        $shaKey = "$prefix.sha256"
        $urlKey = "$prefix.url"
        $requiredKeys += @($fileKey, $shaKey, $urlKey)

        foreach ($key in @($fileKey, $shaKey, $urlKey)) {
            if (-not $properties.ContainsKey($key)) {
                throw "Model manifest is missing property: $key"
            }
        }

        $fileName = [string]$properties[$fileKey]
        $sha256 = [string]$properties[$shaKey]
        $url = [string]$properties[$urlKey]

        if ($fileName.Equals('model-manifest.properties', [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Reserved model file name: $fileName"
        }
        if ([string]::IsNullOrWhiteSpace($fileName) -or
            [System.IO.Path]::IsPathRooted($fileName) -or
            [System.IO.Path]::GetFileName($fileName) -ne $fileName -or
            -not $fileName.EndsWith('.tflite', [System.StringComparison]::Ordinal)) {
            throw "Model file name must be a single basename with a lowercase .tflite extension: $fileName"
        }
        if ($sha256 -notmatch '^[0-9A-Fa-f]{64}$') {
            throw "$shaKey must be a 64-character hexadecimal value"
        }

        $uri = $null
        if (-not [System.Uri]::TryCreate($url, [System.UriKind]::Absolute, [ref]$uri) -or
            $uri.Scheme -ne [System.Uri]::UriSchemeHttps -or
            [string]::IsNullOrWhiteSpace($uri.Host)) {
            throw "$urlKey must be a valid HTTPS URL"
        }

        [PSCustomObject]@{
            FileName = $fileName
            Sha256 = $sha256.ToUpperInvariant()
            Url = $url
        }
    }

    foreach ($key in $properties.Keys) {
        if ($key -notin $requiredKeys) {
            throw "Model manifest contains an unknown property: $key"
        }
    }

    $uniqueFileNames = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )
    foreach ($model in $models) {
        if (-not $uniqueFileNames.Add($model.FileName)) {
            throw "Model file names must be unique: $($model.FileName)"
        }
    }

    return @($models)
}

function Resolve-SafeModelTarget {
    param(
        [Parameter(Mandatory = $true)]
        [string]$OutputRoot,
        [Parameter(Mandatory = $true)]
        [string]$FileName,
        [Parameter(Mandatory = $true)]
        [string]$ManifestPath
    )

    $normalizedOutputRoot = [System.IO.Path]::GetFullPath($OutputRoot).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    $targetPath = [System.IO.Path]::GetFullPath((Join-Path $normalizedOutputRoot $FileName))
    $targetParent = [System.IO.Path]::GetFullPath(
        [System.IO.Directory]::GetParent($targetPath).FullName
    ).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    $normalizedManifestPath = [System.IO.Path]::GetFullPath($ManifestPath)

    if (-not $targetParent.Equals($normalizedOutputRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Model target must be a direct child of the output directory: $targetPath"
    }
    if ($targetPath.Equals($normalizedManifestPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Model target cannot overwrite the manifest: $targetPath"
    }
    return $targetPath
}

function Test-ModelHash {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$ExpectedSha256
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $false
    }
    $actualSha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    return $actualSha256 -eq $ExpectedSha256
}

function Install-ModelFile {
    param(
        [Parameter(Mandatory = $true)]
        [PSCustomObject]$Model,
        [Parameter(Mandatory = $true)]
        [string]$SourceRoot,
        [Parameter(Mandatory = $true)]
        [string]$OutputRoot,
        [Parameter(Mandatory = $true)]
        [string]$ManifestPath
    )

    $sourcePath = Join-Path $SourceRoot $Model.FileName
    $outputPath = Resolve-SafeModelTarget -OutputRoot $OutputRoot -FileName $Model.FileName -ManifestPath $ManifestPath
    $temporaryPath = Join-Path $OutputRoot ('.{0}.{1}.tmp' -f $Model.FileName, [guid]::NewGuid().ToString('N'))

    try {
        if (Test-Path -LiteralPath $sourcePath -PathType Leaf) {
            if (-not (Test-ModelHash -Path $sourcePath -ExpectedSha256 $Model.Sha256)) {
                throw "Local model SHA-256 mismatch: $sourcePath"
            }
            Copy-Item -LiteralPath $sourcePath -Destination $temporaryPath
        } else {
            Write-Host "Local model is missing; downloading $($Model.FileName)"
            Invoke-WebRequest -Uri $Model.Url -OutFile $temporaryPath -UseBasicParsing
        }

        if (-not (Test-ModelHash -Path $temporaryPath -ExpectedSha256 $Model.Sha256)) {
            throw "Model SHA-256 mismatch: $($Model.FileName)"
        }

        $verifiedOutputPath = Resolve-SafeModelTarget -OutputRoot $OutputRoot -FileName $Model.FileName -ManifestPath $ManifestPath
        if (-not $verifiedOutputPath.Equals($outputPath, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Model target changed before installation: $verifiedOutputPath"
        }
        Move-Item -LiteralPath $temporaryPath -Destination $verifiedOutputPath -Force
        Write-Host "Verified $($Model.FileName)  $($Model.Sha256)"
    } finally {
        if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
            Remove-Item -LiteralPath $temporaryPath -Force
        }
    }
}

try {
    $repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
    $manifestPath = Join-Path $repositoryRoot 'app/src/main/assets/models/model-manifest.properties'
    $sourceRoot = Resolve-RepositoryPath -Path $SourceDirectory -RepositoryRoot $repositoryRoot
    $outputRoot = Resolve-RepositoryPath -Path $OutputDirectory -RepositoryRoot $repositoryRoot
    $models = Read-ModelManifest -ManifestPath $manifestPath

    if ($ValidateOnly) {
        foreach ($model in $models) {
            $outputPath = Resolve-SafeModelTarget -OutputRoot $outputRoot -FileName $model.FileName -ManifestPath $manifestPath
            if (-not (Test-ModelHash -Path $outputPath -ExpectedSha256 $model.Sha256)) {
                throw "Output model is missing or has a SHA-256 mismatch: $outputPath"
            }
            Write-Host "Verified $($model.FileName)  $($model.Sha256)"
        }
        exit 0
    }

    [System.IO.Directory]::CreateDirectory($outputRoot) | Out-Null
    foreach ($model in $models) {
        Install-ModelFile -Model $model -SourceRoot $sourceRoot -OutputRoot $outputRoot -ManifestPath $manifestPath
    }
    exit 0
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
