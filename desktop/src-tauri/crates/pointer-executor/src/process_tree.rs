use tokio::process::Child;

pub struct ProcessTree {
    inner: platform::Tree,
}

impl ProcessTree {
    pub fn adopt(child: &Child) -> Self {
        Self {
            inner: platform::Tree::adopt(child),
        }
    }

    pub fn kill(&self) {
        self.inner.kill();
    }

    pub fn leave_running(self) {
        self.inner.leave_running();
    }
}

#[cfg(windows)]
mod platform {
    use std::mem::{size_of, zeroed};
    use std::ptr::{null, null_mut};

    use tokio::process::Child;
    use windows_sys::Win32::Foundation::{CloseHandle, HANDLE};
    use windows_sys::Win32::System::JobObjects::{
        AssignProcessToJobObject, CreateJobObjectW, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
        JOBOBJECT_BASIC_ACCOUNTING_INFORMATION, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
        JobObjectBasicAccountingInformation, JobObjectExtendedLimitInformation,
        QueryInformationJobObject, SetInformationJobObject, TerminateJobObject,
    };

    const KILLED_EXIT_CODE: u32 = 1;

    pub struct Tree {
        job: Option<Job>,
    }

    struct Job(HANDLE);

    unsafe impl Send for Job {}

    impl Tree {
        pub fn adopt(child: &Child) -> Self {
            let job = Job::create().filter(|job| {
                child
                    .raw_handle()
                    .is_some_and(|process| job.assign(process))
            });
            Self { job }
        }

        pub fn kill(&self) {
            if let Some(job) = &self.job {
                job.terminate();
            }
        }

        pub fn leave_running(mut self) {
            if let Some(job) = self.job.take()
                && job.has_active_processes()
            {
                std::mem::forget(job);
            }
        }
    }

    impl Job {
        fn create() -> Option<Self> {
            let handle = unsafe { CreateJobObjectW(null(), null()) };
            if handle.is_null() {
                return None;
            }
            let job = Self(handle);
            let mut limits: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = unsafe { zeroed() };
            limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            let configured = unsafe {
                SetInformationJobObject(
                    job.0,
                    JobObjectExtendedLimitInformation,
                    (&raw const limits).cast(),
                    size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
                )
            };
            (configured != 0).then_some(job)
        }

        fn assign(&self, process: HANDLE) -> bool {
            unsafe { AssignProcessToJobObject(self.0, process) != 0 }
        }

        fn terminate(&self) {
            unsafe {
                TerminateJobObject(self.0, KILLED_EXIT_CODE);
            }
        }

        fn has_active_processes(&self) -> bool {
            let mut accounting: JOBOBJECT_BASIC_ACCOUNTING_INFORMATION = unsafe { zeroed() };
            let queried = unsafe {
                QueryInformationJobObject(
                    self.0,
                    JobObjectBasicAccountingInformation,
                    (&raw mut accounting).cast(),
                    size_of::<JOBOBJECT_BASIC_ACCOUNTING_INFORMATION>() as u32,
                    null_mut(),
                )
            };
            queried == 0 || accounting.ActiveProcesses > 0
        }
    }

    impl Drop for Job {
        fn drop(&mut self) {
            unsafe {
                CloseHandle(self.0);
            }
        }
    }
}

#[cfg(unix)]
mod platform {
    use tokio::process::Child;

    pub struct Tree {
        group: Option<i32>,
    }

    impl Tree {
        pub fn adopt(child: &Child) -> Self {
            Self {
                group: child.id().and_then(|pid| i32::try_from(pid).ok()),
            }
        }

        pub fn kill(&self) {
            if let Some(group) = self.group {
                unsafe {
                    libc::killpg(group, libc::SIGKILL);
                }
            }
        }

        pub fn leave_running(mut self) {
            self.group = None;
        }
    }

    impl Drop for Tree {
        fn drop(&mut self) {
            self.kill();
        }
    }
}
