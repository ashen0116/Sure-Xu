#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/sysconf.h>
#include <elf.h>
#include <jni.h>
#include <android/log.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#ifndef R_AARCH64_GLOB_DAT
#define R_AARCH64_GLOB_DAT 1025
#endif
#ifndef R_AARCH64_JUMP_SLOT
#define R_AARCH64_JUMP_SLOT 1026
#endif

#define TAG "XUHOOK"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static FILE* g_fp = NULL;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_inited = 0;

static void out(const char* s) {
    if (!g_fp) return;
    pthread_mutex_lock(&g_lock);
    fputs(s, g_fp);
    fflush(g_fp);
    pthread_mutex_unlock(&g_lock);
}

typedef int (*connect_t)(int, const struct sockaddr*, socklen_t);
static connect_t g_orig_connect = NULL;

static int my_connect(int sockfd, const struct sockaddr* addr, socklen_t addrlen) {
    if (addr && addr->sa_family == AF_INET) {
        struct sockaddr_in* sin = (struct sockaddr_in*)addr;
        unsigned long ip = ntohl(sin->sin_addr.s_addr);
        int port = ntohs(sin->sin_port);
        char buf[160];
        snprintf(buf, sizeof(buf), "CONNECT %u.%u.%u.%u:%d\n",
            (unsigned)((ip>>24)&0xff),(unsigned)((ip>>16)&0xff),
            (unsigned)((ip>>8)&0xff),(unsigned)(ip&0xff), port);
        out(buf);
    }
    if (g_orig_connect) return g_orig_connect(sockfd, addr, addrlen);
    return -1;
}

static void* get_base(const char* soname, size_t* out_size) {
    FILE* f = fopen("/proc/self/maps","r");
    if(!f) return NULL;
    char line[1024];
    void* base = NULL;
    while(fgets(line,sizeof(line),f)){
        unsigned long long start=0,end=0;
        char perm[8]={0};
        char path[512]={0};
        if(sscanf(line,"%llx-%llx %7s %*s %*s %*s %[^\n]",&start,&end,perm,path)!=4) continue;
        if(!(perm[0]=='r' && perm[1]=='-')) continue;
        if(strstr(path, soname)){
            base=(void*)(unsigned long)start;
            if(out_size) *out_size = (size_t)(end-start);
            break;
        }
    }
    fclose(f);
    return base;
}

static int hook_connect_in(const char* soname, size_t total) {
    void* base = get_base(soname, &total);
    if(!base || total<sizeof(Elf64_Ehdr)) return 0;
    Elf64_Ehdr* eh = (Elf64_Ehdr*)base;
    if(memcmp(eh->e_ident, ELFMAG, SELFMAG)!=0) return 0;
    if(eh->e_phoff==0 || eh->e_phnum==0) return 0;
    Elf64_Phdr* ph = (Elf64_Phdr*)((uint8_t*)base + eh->e_phoff);
    Elf64_Dyn* dyn=NULL;
    for(int i=0;i<eh->e_phnum;i++){
        if(ph[i].p_type==PT_DYNAMIC){ dyn=(Elf64_Dyn*)((uint8_t*)base+ph[i].p_vaddr); break;}
    }
    if(!dyn) return 0;
    Elf64_Sym* symtab=NULL; char* strtab=NULL;
    Elf64_Rela* rela=NULL; size_t rela_sz=0, rela_ent=sizeof(Elf64_Rela);
    for(Elf64_Dyn* d=dyn; d->d_tag!=DT_NULL; d++){
        if(d->d_tag==DT_SYMTAB) symtab=(Elf64_Sym*)((uint8_t*)base+d->d_un.d_ptr);
        else if(d->d_tag==DT_STRTAB) strtab=(char*)((uint8_t*)base+d->d_un.d_ptr);
        else if(d->d_tag==DT_RELA) rela=(Elf64_Rela*)((uint8_t*)base+d->d_un.d_ptr);
        else if(d->d_tag==DT_RELASZ) rela_sz=d->d_un.d_val;
        else if(d->d_tag==DT_RELAENT && d->d_un.d_val) rela_ent=d->d_un.d_val;
    }
    if(!symtab||!strtab||!rela||rela_sz<rela_ent) return 0;
    int count=0;
    size_t n = rela_sz/rela_ent;
    for(size_t i=0;i<n;i++){
        Elf64_Rela* r=&rela[i];
        unsigned type = (unsigned)(r->r_info & 0xffffffffULL);
        uint64_t symidx = r->r_info >> 32;
        if(type!=R_AARCH64_GLOB_DAT && type!=R_AARCH64_JUMP_SLOT) continue;
        Elf64_Sym* sym = (Elf64_Sym*)((uint8_t*)symtab + symidx*sizeof(Elf64_Sym));
        if((uint8_t*)sym < (uint8_t*)base || (uint8_t*)(sym+1) > (uint8_t*)base+total) continue;
        char* name = strtab + sym->st_name;
        if(strcmp(name,"connect")!=0) continue;
        void** got = (void**)((uint8_t*)base + r->r_offset);
        if((uint8_t*)got < (uint8_t*)base || (uint8_t*)(got+1) > (uint8_t*)base+total) continue;
        if(g_orig_connect==NULL) g_orig_connect=(connect_t)(*got);
        long ps = sysconf(_SC_PAGESIZE);
        uintptr_t page = (uintptr_t)got & ~((uintptr_t)ps-1);
        if(mprotect((void*)page, (size_t)ps, PROT_READ|PROT_WRITE)!=0) continue;
        *got = (void*)my_connect;
        mprotect((void*)page, (size_t)ps, PROT_READ);
        count++;
        break;
    }
    return count;
}

static const char* TARGETS[] = {"libmonochrome.so","libcronet.so","libwebviewchromium.so","libchrome.so"};

JNIEXPORT void JNICALL Java_com_surexu_sesame_hook_NativeProbe_initNative(JNIEnv* env, jclass cls) {
    if(g_inited) return;
    g_inited = 1;
    g_fp = fopen("/data/local/tmp/nethook.txt","a");
    for(int i=0;i<(int)(sizeof(TARGETS)/sizeof(TARGETS[0]));i++){
        size_t dummy=0;
        int c = hook_connect_in(TARGETS[i], dummy);
        if(c>0){
            char b[160]; snprintf(b,sizeof(b),"HOOKED %s count=%d\n",TARGETS[i],c); out(b);
        }
    }
    out("NETHOOK_INIT_DONE\n");
    LOG("NETHOOK_INIT_DONE");
}
