param(
    [Parameter(Mandatory = $true)][int]$ProcessId,
    [Parameter(Mandatory = $true)][string]$StepsFile,
    [int]$TimeoutSeconds = 15,
    [int]$SettleMilliseconds = 300
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$Automation = [System.Windows.Automation.AutomationElement]
$Scope = [System.Windows.Automation.TreeScope]
$Deadline = [TimeSpan]::FromSeconds($TimeoutSeconds)

function Wait-For([scriptblock]$Probe, [string]$What) {
    $until = (Get-Date) + $Deadline
    do {
        $found = & $Probe
        if ($null -ne $found) {
            return $found
        }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $until)
    throw "Timed out waiting for $What"
}

function Get-AppWindow {
    $condition = New-Object System.Windows.Automation.PropertyCondition($Automation::ProcessIdProperty, $ProcessId)
    Wait-For { $Automation::RootElement.FindFirst($Scope::Children, $condition) } "the window of process $ProcessId"
}

function Get-ControlType([string]$Role) {
    switch ($Role) {
        'button' { return [System.Windows.Automation.ControlType]::Button }
        'entry' { return [System.Windows.Automation.ControlType]::Edit }
        default { return $null }
    }
}

function Find-Control($Window, $Target) {
    $byName = New-Object System.Windows.Automation.PropertyCondition($Automation::NameProperty, [string]$Target.name)
    $type = Get-ControlType ([string]$Target.role)
    $condition = $byName
    if ($null -ne $type) {
        $byType = New-Object System.Windows.Automation.PropertyCondition($Automation::ControlTypeProperty, $type)
        $condition = New-Object System.Windows.Automation.AndCondition($byName, $byType)
    }
    Wait-For {
        $element = $Window.FindFirst($Scope::Descendants, $condition)
        if ($null -ne $element -and -not $element.Current.IsOffscreen) { $element } else { $null }
    } "$($Target.role) '$($Target.name)'"
}

function Invoke-Control($Element) {
    $pattern = $null
    if ($Element.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$pattern)) {
        $pattern.Invoke()
        return
    }
    $Element.SetFocus()
}

function Set-Text($Element, [string]$Text) {
    $pattern = $null
    if (-not $Element.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$pattern)) {
        throw "The focused control has no value pattern"
    }
    $pattern.SetValue($Text)
}

$steps = Get-Content -Raw -Path $StepsFile | ConvertFrom-Json
$window = Get-AppWindow
$focused = $null
foreach ($step in $steps) {
    $action = [string]$step[0]
    switch ($action) {
        'click' {
            $element = Find-Control $window $step[1]
            Invoke-Control $element
            if ([string]$step[1].role -eq 'entry') {
                $focused = $element
            }
        }
        'type' {
            if ($null -eq $focused) {
                throw "Nothing to type into"
            }
            Set-Text $focused ([string]$step[1])
        }
        'waitTree' {
            [void](Find-Control $window $step[1])
        }
        default {
            throw "Unknown step $action"
        }
    }
    Start-Sleep -Milliseconds $SettleMilliseconds
}
Write-Output "replayed $($steps.Count) steps"
