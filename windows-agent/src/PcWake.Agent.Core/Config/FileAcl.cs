using System.Security.AccessControl;
using System.Security.Principal;

namespace PcWake.Agent.Core.Config;

/// <summary>
/// Restricts a directory to SYSTEM and the local Administrators group, and disables
/// inheritance. Files created inside inherit this ACL.
/// </summary>
public static class FileAcl
{
    public static void RestrictDirectory(string path)
    {
        if (!OperatingSystem.IsWindows())
        {
            return;
        }

        Directory.CreateDirectory(path);
        var info = new DirectoryInfo(path);
        var security = info.GetAccessControl(AccessControlSections.Owner | AccessControlSections.Group | AccessControlSections.Access);
        security.SetAccessRuleProtection(true, false);

        var system = new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null);
        var admins = new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null);
        const InheritanceFlags inheritance = InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit;

        security.AddAccessRule(new FileSystemAccessRule(
            system,
            FileSystemRights.FullControl,
            inheritance,
            PropagationFlags.None,
            AccessControlType.Allow));
        security.AddAccessRule(new FileSystemAccessRule(
            admins,
            FileSystemRights.FullControl,
            inheritance,
            PropagationFlags.None,
            AccessControlType.Allow));

        info.SetAccessControl(security);
    }

    public static void RestrictFile(string path)
    {
        if (!OperatingSystem.IsWindows() || !File.Exists(path))
        {
            return;
        }

        var info = new FileInfo(path);
        var security = info.GetAccessControl(AccessControlSections.Access);
        security.SetAccessRuleProtection(true, false);

        var system = new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null);
        var admins = new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null);

        security.AddAccessRule(new FileSystemAccessRule(
            system,
            FileSystemRights.FullControl,
            AccessControlType.Allow));
        security.AddAccessRule(new FileSystemAccessRule(
            admins,
            FileSystemRights.FullControl,
            AccessControlType.Allow));

        info.SetAccessControl(security);
    }
}
