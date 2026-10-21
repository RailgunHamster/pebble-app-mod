@echo off
rem Double-click: push this folder (patches + workflow) to RailgunHamster/pebble-app-mod,
rem branch "main". Create the repository first on github.com/new (empty).
rem Run it again whenever the files here change; GitHub then builds a new APK.
rem Git asks you to sign in to GitHub the first time (browser window).
setlocal
cd /d "%~dp0"
set "REPO=https://github.com/RailgunHamster/pebble-app-mod.git"
set "NAME=RailgunHamster"
set "MAIL=RailgunHamster@users.noreply.github.com"
if exist build.yml (
  if not exist .github\workflows mkdir .github\workflows
  move /y build.yml .github\workflows\build.yml >nul
)
if not exist .git git init -q -b main
git add -A
git -c user.name=%NAME% -c user.email=%MAIL% commit -q -m "Update patches / workflow" 2>nul
git push -f %REPO% main:main
if errorlevel 1 (
  echo !! push failed: does github.com/RailgunHamster/pebble-app-mod exist?
) else (
  echo pushed. Build: https://github.com/RailgunHamster/pebble-app-mod/actions
)
pause
