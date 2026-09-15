#!/usr/bin/env bash
# usage: scripts/freeze.sh <port>   - suspend the process listening on <port> (SIGSTOP; NtSuspendProcess on Windows)
#        scripts/freeze.sh <port> --thaw   - resume it
# A frozen service keeps its port open and answers nothing: nastier than a crash, and what an overloaded
# service looks like from the outside.
set -euo pipefail
PORT="${1:?port}"; ACTION="${2:-}"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    powershell.exe -NoProfile -Command "
      \$sig = '[DllImport(\"ntdll.dll\")] public static extern int NtSuspendProcess(IntPtr h); [DllImport(\"ntdll.dll\")] public static extern int NtResumeProcess(IntPtr h);'
      Add-Type -MemberDefinition \$sig -Name Nt -Namespace Lf | Out-Null
      \$owner = (Get-NetTCPConnection -LocalPort $PORT -State Listen | Select-Object -First 1).OwningProcess
      \$p = Get-Process -Id \$owner
      if ('$ACTION' -eq '--thaw') { [Lf.Nt]::NtResumeProcess(\$p.Handle) | Out-Null; Write-Output \"resumed pid \$owner\" }
      else { [Lf.Nt]::NtSuspendProcess(\$p.Handle) | Out-Null; Write-Output \"suspended pid \$owner\" }
    " ;;
  *)
    PID=$(lsof -ti:"$PORT" | head -1)
    if [ "$ACTION" = "--thaw" ]; then kill -CONT "$PID"; echo "resumed pid $PID"; else kill -STOP "$PID"; echo "suspended pid $PID"; fi ;;
esac
