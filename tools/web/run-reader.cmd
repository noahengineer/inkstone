@echo off
rem The Inkstone reader, for a PowerShell that refuses to run .ps1 files.
rem
rem   tools\web\run-reader.cmd -Open
rem
rem Windows defaults `CurrentUser` execution policy to Restricted, so `run-reader.ps1` fails with
rem "running scripts is disabled on this system" on a machine where nothing has changed it. A .cmd
rem file is not subject to execution policy at all, which is the whole reason this exists — it
rem needs no `Set-ExecutionPolicy`, no `-ExecutionPolicy Bypass`, and no decision about either.
rem
rem It is deliberately thinner than the .ps1: `server.py` already loads the config, checks that the
rem URL is https, and prints the same advice when something is missing. The only thing a launcher
rem has to do is find an interpreter.
rem
rem Arguments are passed straight through, so `-Open`, `--open`, `--mirror <path>` and the rest all
rem work; server.py's argparse accepts the double-dash forms.

setlocal

rem ⚠️ `py` FIRST, and the order matters on this machine class.
rem
rem With Python installed from python.org but the Microsoft Store app-execution aliases still
rem enabled, `python` and `python3` resolve to stubs in WindowsApps that fail with "The system
rem cannot find the path specified" — while `py`, the official launcher, works. Measured on the
rem machine this was written for: `py --version` printed 3.14.5 and both of the others failed.
rem
rem So each candidate is TESTED rather than merely found. `where` would report the stub as a
rem perfectly good match.

set "INKSTONE_PY="
for %%I in (py python python3) do (
    if not defined INKSTONE_PY (
        %%I -c "import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)" >nul 2>&1
        if not errorlevel 1 set "INKSTONE_PY=%%I"
    )
)

if not defined INKSTONE_PY (
    echo.
    echo   No working Python 3.10+ found. Tried: py, python, python3.
    echo.
    echo   If Python is installed but `python` opens the Microsoft Store, turn the aliases off in
    echo   Settings ^> Apps ^> Advanced app settings ^> App execution aliases - or just install the
    echo   py launcher, which this prefers anyway.
    echo.
    exit /b 2
)

"%INKSTONE_PY%" "%~dp0server.py" %*
exit /b %errorlevel%
