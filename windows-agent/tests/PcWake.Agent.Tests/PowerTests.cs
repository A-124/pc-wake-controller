using System.Text;
using PcWake.Agent.Core.Audit;
using PcWake.Agent.Core.Power;
using PcWake.Agent.Core.Protocol;

namespace PcWake.Agent.Tests;

public sealed class PowerTests
{
    private const string SystemDirectory = @"C:\Windows\System32";
    private const string ExpectedExecutable = @"C:\Windows\System32\shutdown.exe";

    [Fact]
    public async Task WindowsController_UsesExactShutdownInvocation()
    {
        var runner = new FakeProcessRunner();
        var controller = new WindowsPowerController(runner, SystemDirectory);

        var result = await controller.ShutdownAsync(15, "PC Wake Controller remote shutdown", CancellationToken.None);

        Assert.True(result.Success);
        var call = Assert.Single(runner.Calls);
        Assert.Equal(ExpectedExecutable, call.FileName);
        Assert.Equal(
            new[] { "/s", "/t", "15", "/d", "p:4:1", "/c", "PC Wake Controller remote shutdown" },
            call.Arguments);
    }

    [Fact]
    public async Task WindowsController_UsesExactRestartAndCancelInvocations()
    {
        var runner = new FakeProcessRunner();
        var controller = new WindowsPowerController(runner, SystemDirectory);

        await controller.RestartAsync(5, "restart reason", CancellationToken.None);
        await controller.CancelAsync(CancellationToken.None);

        Assert.Equal(2, runner.Calls.Count);
        Assert.Equal(
            new[] { "/r", "/t", "5", "/d", "p:4:1", "/c", "restart reason" },
            runner.Calls[0].Arguments);
        Assert.Equal(ExpectedExecutable, runner.Calls[0].FileName);
        Assert.Equal(new[] { "/a" }, runner.Calls[1].Arguments);
        Assert.Equal(ExpectedExecutable, runner.Calls[1].FileName);
    }

    [Fact]
    public void GraceIsClampedAndReasonIsSanitized()
    {
        var high = FixedPowerCommand.Shutdown(500, "reason", SystemDirectory);
        var low = FixedPowerCommand.Restart(-10, "reason", SystemDirectory);
        Assert.Equal("60", high.Arguments[2]);
        Assert.Equal("0", low.Arguments[2]);

        var sanitized = ReasonSanitizer.Sanitize("  he said \"hi\" and 'bye'\r\n\u0001  ", "fallback");
        Assert.Equal("he said hi and bye", sanitized);
        Assert.Equal("fallback", ReasonSanitizer.Sanitize("\"'\"", "fallback"));
        Assert.Equal(200, ReasonSanitizer.Sanitize(new string('x', 300), "fallback").Length);
    }

    [Fact]
    public async Task GraceDefaultsAndClamping_AreReflectedInTheAcceptedCommand()
    {
        var clock = new FakeClock();
        var audit = new RecordingAuditLog();

        await using (var service = new PowerCommandService(
            new FakePowerController(), clock, new ManualPowerDelay(), audit))
        {
            var defaultShutdown = await service.StartAsync(PowerAction.Shutdown, null, null);
            Assert.Equal(PowerStartError.None, defaultShutdown.Error);
            Assert.Equal(15, defaultShutdown.Command!.GraceSeconds);
            Assert.Equal(clock.UtcNow.ToUnixTimeSeconds() + 15, defaultShutdown.Command.ExecuteAt);
        }

        await using (var service = new PowerCommandService(
            new FakePowerController(), clock, new ManualPowerDelay(), audit))
        {
            var clampedLow = await service.StartAsync(PowerAction.Restart, -100, null);
            Assert.Equal(0, clampedLow.Command!.GraceSeconds);
        }

        await using (var service = new PowerCommandService(
            new FakePowerController(), clock, new ManualPowerDelay(), audit))
        {
            var clampedHigh = await service.StartAsync(PowerAction.Shutdown, 999, "reason");
            Assert.Equal(60, clampedHigh.Command!.GraceSeconds);
        }

        await using (var service = new PowerCommandService(
            new FakePowerController(), clock, new ManualPowerDelay(), audit))
        {
            var sleep = await service.StartAsync(PowerAction.Sleep, 50, null);
            Assert.Equal(3, sleep.Command!.GraceSeconds);
            Assert.Equal(clock.UtcNow.ToUnixTimeSeconds() + 3, sleep.Command.ExecuteAt);
        }
    }

    [Fact]
    public async Task OnlyOnePendingCommand_IsAllowed()
    {
        var controller = new FakePowerController();
        var delay = new ManualPowerDelay();
        await using var service = new PowerCommandService(
            controller, new FakeClock(), delay, new RecordingAuditLog());

        var first = await service.StartAsync(PowerAction.Shutdown, 15, null);
        Assert.Equal(PowerStartError.None, first.Error);
        await WaitHelpers.WaitUntilAsync(() => delay.Requests.Count == 1);

        var second = await service.StartAsync(PowerAction.Restart, 15, null);
        Assert.Equal(PowerStartError.CommandPending, second.Error);
        Assert.Null(second.Command);
        Assert.Single(controller.ShutdownCalls);
        Assert.Empty(controller.RestartCalls);
    }

    [Fact]
    public async Task Sleep_IsScheduledAfterThreeSecondsThenCallsSuspendOnce()
    {
        var controller = new FakePowerController();
        var delay = new ManualPowerDelay();
        await using var service = new PowerCommandService(
            controller, new FakeClock(), delay, new RecordingAuditLog());

        var start = await service.StartAsync(PowerAction.Sleep, null, "night");
        Assert.Equal(PowerStartError.None, start.Error);
        await WaitHelpers.WaitUntilAsync(() => delay.Requests.Count == 1);
        Assert.Equal(TimeSpan.FromSeconds(3), delay.Requests[0]);
        Assert.Equal(0, controller.SleepCalls);
        Assert.Equal("pending", service.GetSnapshot().PendingCommand!.Status.ToWireName());

        delay.ReleaseNext();
        await WaitHelpers.WaitUntilAsync(() => controller.SleepCalls == 1);
        await WaitHelpers.WaitUntilAsync(
            () => service.GetSnapshot().LastCommand?.Status == PowerCommandStatus.Executed);
        Assert.Equal(1, controller.SleepCalls);
        Assert.Null(service.GetSnapshot().PendingCommand);
        Assert.Equal("executed", service.GetSnapshot().LastCommand!.Status.ToWireName());
    }

    [Fact]
    public async Task Shutdown_StaysPendingThroughGraceThenTransitionsToExecuted()
    {
        var controller = new FakePowerController();
        var delay = new ManualPowerDelay();
        await using var service = new PowerCommandService(
            controller, new FakeClock(), delay, new RecordingAuditLog());

        var start = await service.StartAsync(PowerAction.Shutdown, 15, null);
        Assert.Equal("pending", start.Command!.Status.ToWireName());
        await WaitHelpers.WaitUntilAsync(() => delay.Requests.Count == 1);
        Assert.Single(controller.ShutdownCalls);
        Assert.Equal("pending", service.GetSnapshot().PendingCommand!.Status.ToWireName());

        delay.ReleaseNext();
        await WaitHelpers.WaitUntilAsync(
            () => service.GetSnapshot().LastCommand?.Status == PowerCommandStatus.Executed);
        Assert.Null(service.GetSnapshot().PendingCommand);
        Assert.Equal(PowerCommandStatus.Executed, service.GetSnapshot().LastCommand!.Status);
    }

    [Fact]
    public async Task ShutdownFailure_IsReportedAsFailed()
    {
        var controller = new FakePowerController
        {
            ShutdownResult = PowerNativeResult.Fail("Native command failed with exit code 5.", 5)
        };
        await using var service = new PowerCommandService(
            controller, new FakeClock(), new ImmediatePowerDelay(), new RecordingAuditLog());

        var start = await service.StartAsync(PowerAction.Shutdown, 0, null);
        Assert.Equal(PowerStartError.None, start.Error);
        await WaitHelpers.WaitUntilAsync(
            () => service.GetSnapshot().LastCommand?.Status == PowerCommandStatus.Failed);
        Assert.Contains("exit code 5", service.GetSnapshot().LastCommand!.Error);
        Assert.Null(service.GetSnapshot().PendingCommand);
    }

    [Fact]
    public async Task Cancel_InvokesShutdownSlashAOnceAndMarksThePendingCommandCancelled()
    {
        var controller = new FakePowerController();
        var delay = new ManualPowerDelay();
        await using var service = new PowerCommandService(
            controller, new FakeClock(), delay, new RecordingAuditLog());

        var start = await service.StartAsync(PowerAction.Shutdown, 15, null);
        await WaitHelpers.WaitUntilAsync(() => delay.Requests.Count == 1);

        var cancel = await service.CancelAsync();
        Assert.True(cancel.Cancelled);
        Assert.Equal(1, controller.CancelCalls);
        Assert.Null(service.GetSnapshot().PendingCommand);
        Assert.Equal(PowerCommandStatus.Cancelled, service.GetSnapshot().LastCommand!.Status);

        var secondCancel = await service.CancelAsync();
        Assert.False(secondCancel.Cancelled);
        Assert.Equal(1, controller.CancelCalls);
    }

    [Fact]
    public async Task SleepCannotBeCancelled()
    {
        var controller = new FakePowerController();
        var delay = new ManualPowerDelay();
        await using var service = new PowerCommandService(
            controller, new FakeClock(), delay, new RecordingAuditLog());

        await service.StartAsync(PowerAction.Sleep, null, null);
        await WaitHelpers.WaitUntilAsync(() => delay.Requests.Count == 1);
        var cancel = await service.CancelAsync();

        Assert.False(cancel.Cancelled);
        Assert.Equal(0, controller.CancelCalls);
        Assert.Equal("sleep", service.GetSnapshot().PendingCommand!.Action.ToWireName());
    }

    [Fact]
    public async Task Audit_RecordsCommandLifecycleWithoutArbitraryShellText()
    {
        var audit = new RecordingAuditLog();
        var controller = new FakePowerController();
        var delay = new ManualPowerDelay();
        await using var service = new PowerCommandService(
            controller, new FakeClock(), delay, audit);

        await service.StartAsync(PowerAction.Shutdown, 15, "bad \"quoted\" reason");
        await WaitHelpers.WaitUntilAsync(() => delay.Requests.Count == 1);
        await service.CancelAsync();

        var events = audit.Entries.Select(e => e.EventName).ToList();
        Assert.Contains(AuditEvents.CommandAccepted, events);
        Assert.Contains(AuditEvents.CommandCancelled, events);
        var serialized = string.Join("\n", audit.Entries.Select(e =>
            System.Text.Json.JsonSerializer.Serialize(e.Fields)));
        Assert.DoesNotContain("bad \"quoted\" reason", serialized);
    }
}
