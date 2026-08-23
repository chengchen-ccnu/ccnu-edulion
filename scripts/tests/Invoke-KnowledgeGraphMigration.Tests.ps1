$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$scriptsRoot = Split-Path -Parent $here
$migrationScript = Join-Path $scriptsRoot 'Invoke-KnowledgeGraphMigration.ps1'
$knowledgePointTypeName = [string]([char]0x77e5) + [char]0x8bc6 + [char]0x70b9

function Write-GraphFixture([string]$Directory, [switch]$Cycle) {
    New-Item -ItemType Directory -Path $Directory | Out-Null
    $nodes = [ordered]@{ totalCount = 2; mathNodes = @(
        [ordered]@{ identity=1; labels=@('知识点'); elementId='ignored-1'; properties=[ordered]@{ knowName='整数';knowTypeColour='#112233';knowTypeId='TYPE_A';knowTypeName=$knowledgePointTypeName;lvlSortIndex=1;nodeKind='point';discipline='math';eduStage='03';knowId='source-a';parentId='group-1';status=0 } },
        [ordered]@{ identity=2; labels=@('知识点'); elementId='ignored-2'; properties=[ordered]@{ knowName='有理数';knowTypeColour='#112233';knowTypeId='TYPE_A';knowTypeName=$knowledgePointTypeName;lvlSortIndex=2;nodeKind='point';discipline='math';eduStage='03';knowId='source-b';parentId='group-1';status=0 } }
    ) }
    $relation = { param($a,$b) [ordered]@{ a=$a;r=[ordered]@{type='PRECEDES';properties=[ordered]@{id='not-unique'}};b=$b } }
    $a=$nodes.mathNodes[0]; $b=$nodes.mathNodes[1]
    $relations = @(&$relation $a $b)
    if ($Cycle) { $relations += @(&$relation $b $a) }
    $nodePath=Join-Path $Directory 'nodes.json'; $relationPath=Join-Path $Directory 'relations.json'
    [IO.File]::WriteAllText($nodePath, ($nodes|ConvertTo-Json -Depth 12), (New-Object Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText($relationPath, ($relations|ConvertTo-Json -Depth 12), (New-Object Text.UTF8Encoding($false)))
    return [pscustomobject]@{Node=$nodePath;Relation=$relationPath}
}

function Start-GraphServer([object[]]$Responses) {
    $listener=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,0);$listener.Start();$port=([Net.IPEndPoint]$listener.LocalEndpoint).Port
    $job=Start-ThreadJob -ArgumentList $listener,$Responses -ScriptBlock {
        param($Listener,$Responses)
        foreach($response in $Responses){
            $client=$Listener.AcceptTcpClient();try{
                $stream=$client.GetStream();$headerBytes=New-Object Collections.Generic.List[byte];$tail=''
                while($tail -ne "`r`n`r`n"){$value=$stream.ReadByte();if($value -lt 0){break};[void]$headerBytes.Add([byte]$value);$tail=($tail+[char]$value);if($tail.Length -gt 4){$tail=$tail.Substring($tail.Length-4)}}
                $header=[Text.Encoding]::ASCII.GetString($headerBytes.ToArray());$requestLine=($header -split "`r`n")[0];$length=0
                if($header -match '(?im)^Content-Length:\s*(\d+)\s*$'){$length=[int]$Matches[1]}
                $bodyBytes=New-Object byte[] $length;$read=0;while($read -lt $length){$count=$stream.Read($bodyBytes,$read,$length-$read);if($count -le 0){break};$read+=$count}
                $body=if($read){(New-Object Text.UTF8Encoding($false,$true)).GetString($bodyBytes,0,$read)}else{''};[pscustomobject]@{RequestLine=$requestLine;Body=$body}
                $bytes=[Text.Encoding]::UTF8.GetBytes([string]$response.Body);$head=[Text.Encoding]::ASCII.GetBytes("HTTP/1.1 $($response.Status)`r`nContent-Type: application/json`r`nContent-Length: $($bytes.Length)`r`nConnection: close`r`n`r`n")
                $stream.Write($head,0,$head.Length);$stream.Write($bytes,0,$bytes.Length);$stream.Flush()
            }finally{$client.Close()}
        }
    }
    return [pscustomobject]@{Listener=$listener;Job=$job;Port=$port}
}
function Response([string]$Status,$Body){[pscustomobject]@{Status=$Status;Body=if($Body-is[string]){$Body}else{$Body|ConvertTo-Json -Depth 15 -Compress}}}

Describe 'Invoke-KnowledgeGraphMigration safety and graph semantics' {
    It '默认 DryRun 分批提交，并把文件 b 映射为 prerequisite、a 映射为 dependent' {
        $source=Write-GraphFixture (Join-Path $TestDrive 'source')
        $task='11111111-1111-4111-8111-111111111111'
        $responses=@(
            (Response '201 Created' ([ordered]@{id=$task;status='AWAITING_NODES'})),
            (Response '200 OK' ([ordered]@{importTaskId=$task;batchId='x';replayed=$false;acceptedCount=1;status='AWAITING_NODES'})),
            (Response '200 OK' ([ordered]@{importTaskId=$task;batchId='x';replayed=$false;acceptedCount=1;status='AWAITING_RELATIONS'})),
            (Response '200 OK' ([ordered]@{importTaskId=$task;batchId='x';replayed=$false;acceptedCount=1;status='COMPLETED'})),
            (Response '200 OK' ([ordered]@{id=$task;status='COMPLETED';summary=[ordered]@{nodeCount=2;relationCount=1}}))
        );$server=Start-GraphServer $responses;$previous=$env:EDU_MIGRATION_ACCESS_TOKEN;$env:EDU_MIGRATION_ACCESS_TOKEN='fixture-token'
        try{
            $result=& $migrationScript -NodeFile $source.Node -RelationFile $source.Relation -SourceSystem 'KG_FIXTURE' -ApiBaseUrl "http://127.0.0.1:$($server.Port)" -BatchSize 1 -CheckpointPath (Join-Path $TestDrive 'checkpoint.json')
            $result.action|Should Be 'DryRun';$result.local.nodeCount|Should Be 2;$result.local.relationCount|Should Be 1;$result.formalImportExecuted|Should Be $false
            Wait-Job $server.Job|Out-Null;$requests=@(Receive-Job $server.Job | Where-Object { $_.PSObject.Properties['Body'] });$requests.Count|Should Be 5
            $relationText=[string]$requests[3].Body
            $nodeText=[string]$requests[1].Body
            { $relationText | ConvertFrom-Json } | Should Not Throw
            { $nodeText | ConvertFrom-Json } | Should Not Throw
            $relationText|Should Match '"prerequisiteSourceKnowId":"source-b"'
            $relationText|Should Match '"dependentSourceKnowId":"source-a"'
            $nodeText|Should Match '整数'
            $nodeText|Should Match '"normalizedStage":"JUNIOR"'
            $nodeText|Should Match ('"knowledgeTypeName":"' + [regex]::Escape($knowledgePointTypeName) + '"')
            $checkpoint=[IO.File]::ReadAllText((Join-Path $TestDrive 'checkpoint.json'),[Text.Encoding]::UTF8)
            $checkpoint|Should Not Match 'fixture-token|source\\|nodes.json|relations.json|整数|source-a'
        }finally{$env:EDU_MIGRATION_ACCESS_TOKEN=$previous;$server.Listener.Stop();Stop-Job $server.Job -ErrorAction SilentlyContinue;Remove-Job $server.Job -Force -ErrorAction SilentlyContinue}
    }

    It '正式 Import 在发请求前要求导入确认和四项语义确认' {
        $source=Write-GraphFixture (Join-Path $TestDrive 'gated');$env:EDU_MIGRATION_ACCESS_TOKEN='fixture-token'
        $caught=$null;try{& $migrationScript -Action Import -NodeFile $source.Node -RelationFile $source.Relation -SourceSystem 'KG_FIXTURE' -CheckpointPath (Join-Path $TestDrive 'gated.json')}catch{$caught=$_.Exception.Message}
        $caught|Should Match 'semantic confirmations'
        Test-Path (Join-Path $TestDrive 'gated.json') | Should Be $false
    }

    It '本地全图预检识别环且不输出完整来源标识列表' {
        $source=Write-GraphFixture (Join-Path $TestDrive 'cycle') -Cycle;$task='22222222-2222-4222-8222-222222222222'
        $responses=@((Response '201 Created' ([ordered]@{id=$task;status='AWAITING_NODES'})),(Response '200 OK' @{}),(Response '200 OK' @{}),(Response '200 OK' ([ordered]@{id=$task;status='COMPLETED_WITH_ISSUES';summary=$null})))
        $server=Start-GraphServer $responses;$previous=$env:EDU_MIGRATION_ACCESS_TOKEN;$env:EDU_MIGRATION_ACCESS_TOKEN='cycle-token'
        try{$serialized=(& $migrationScript -NodeFile $source.Node -RelationFile $source.Relation -SourceSystem 'KG_CYCLE' -ApiBaseUrl "http://127.0.0.1:$($server.Port)" -CheckpointPath (Join-Path $TestDrive 'cycle-checkpoint.json'))|ConvertTo-Json -Depth 12;$result=$serialized|ConvertFrom-Json;$result.local.cycleDetected|Should Be $true;$serialized|Should Not Match 'source-a|source-b|cycle-token'}finally{$env:EDU_MIGRATION_ACCESS_TOKEN=$previous;$server.Listener.Stop();Stop-Job $server.Job -ErrorAction SilentlyContinue;Remove-Job $server.Job -Force -ErrorAction SilentlyContinue}
    }

    It '401 只报告安全状态且检查点不前移' {
        $source=Write-GraphFixture (Join-Path $TestDrive 'unauthorized');$server=Start-GraphServer @((Response '401 Unauthorized' '{"message":"secret source-a"}'));$previous=$env:EDU_MIGRATION_ACCESS_TOKEN;$env:EDU_MIGRATION_ACCESS_TOKEN='secret-token'
        try{$caught=$null;try{& $migrationScript -NodeFile $source.Node -RelationFile $source.Relation -SourceSystem 'KG_401' -ApiBaseUrl "http://127.0.0.1:$($server.Port)" -CheckpointPath (Join-Path $TestDrive '401.json')}catch{$caught=$_.Exception.Message};$caught|Should Match 'HTTP 401';$caught|Should Not Match 'secret source-a|secret-token';Test-Path (Join-Path $TestDrive '401.json')|Should Be $false}finally{$env:EDU_MIGRATION_ACCESS_TOKEN=$previous;$server.Listener.Stop();Stop-Job $server.Job -ErrorAction SilentlyContinue;Remove-Job $server.Job -Force -ErrorAction SilentlyContinue}
    }

    It '脚本不包含数据库连接或凭据持久化路径' {
        $text=[IO.File]::ReadAllText($migrationScript,[Text.Encoding]::UTF8)
        $text|Should Not Match 'mysql|jdbc|Invoke-Sqlcmd|local\.env|SetEnvironmentVariable'
        $text|Should Match 'EDU_MIGRATION_ACCESS_TOKEN'
        $text|Should Match 'UTF8Encoding\]\(\$false\)|UTF8Encoding\(\$false\)'
    }
}
