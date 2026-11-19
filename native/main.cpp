#include <csignal>
#include <sys/prctl.h>
#include <unistd.h>
int llama_server(int argc, char ** argv);
int main(int argc, char ** argv) {
    const pid_t parent = getppid();
    if (parent == 1 || prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent) {
        return 125;
    }
    return llama_server(argc, argv);
}
