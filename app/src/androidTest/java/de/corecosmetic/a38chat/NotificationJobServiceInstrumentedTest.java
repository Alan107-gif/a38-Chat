package de.corecosmetic.a38chat;

import android.Manifest;
import android.app.Instrumentation;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class NotificationJobServiceInstrumentedTest {
    private static final String TEST_ACCOUNT = "A38NotificationScheduleTest";

    @Test
    public void networkConstrainedJobsCanBeScheduledWithoutCrashing() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        PackageManager packages = context.getPackageManager();

        assertEquals(
                "ACCESS_NETWORK_STATE is mandatory for jobs with a connectivity constraint",
                PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE)
        );
        ServiceInfo service = packages.getServiceInfo(
                new ComponentName(context, ChatNotificationService.class),
                PackageManager.GET_META_DATA
        );
        assertTrue("JobScheduler must be able to bind the notification service", service.exported);
        assertEquals("android.permission.BIND_JOB_SERVICE", service.permission);

        AccountStore store = new AccountStore(context);
        boolean notificationsEnabled = store.notificationsEnabled();
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                instrumentation.getUiAutomation().grantRuntimePermission(
                        context.getPackageName(),
                        Manifest.permission.POST_NOTIFICATIONS
                );
            }
            store.setNotificationsEnabled(true);
            store.upsertAccount(new AccountStore.Account(TEST_ACCOUNT, "invalid-test-token"));

            ChatNotificationService.startIfEnabled(context);
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            assertNotNull(scheduler);
            JobInfo periodic = scheduler.getPendingJob(38011);
            JobInfo immediate = scheduler.getPendingJob(38012);
            assertNotNull("Periodic notification job must be registered", periodic);
            assertNotNull("Immediate notification job must be registered", immediate);
            ComponentName expected = new ComponentName(context, ChatNotificationService.class);
            assertEquals(expected, periodic.getService());
            assertEquals(expected, immediate.getService());
        } finally {
            ChatNotificationService.stop(context);
            store.removeAccount(TEST_ACCOUNT);
            store.setNotificationsEnabled(notificationsEnabled);
        }
    }
}
