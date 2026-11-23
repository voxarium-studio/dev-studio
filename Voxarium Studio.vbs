Option Explicit
Dim shell, files, root, executable, arguments
Set shell = CreateObject("WScript.Shell")
Set files = CreateObject("Scripting.FileSystemObject")
root = files.GetParentFolderName(WScript.ScriptFullName)
executable = root & "\desktop\dist\win-unpacked\Voxarium Studio.exe"
arguments = ""
If Not files.FileExists(executable) Then
  executable = root & "\desktop\node_modules\electron\dist\electron.exe"
  arguments = " """ & root & "\desktop" & """"
End If
If files.FileExists(executable) Then
  shell.CurrentDirectory = root
  shell.Run """" & executable & """" & arguments, 0, False
Else
  MsgBox "Voxarium Studio is not installed. See README.md.", 16, "Voxarium Studio"
End If
