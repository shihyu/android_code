#ifndef VIRTIO_GOLDFISH_PIPE
#define VIRTIO_GOLDFISH_PIPE

/* An override of virtio-gpu-3d (virgl) that runs goldfish pipe.  One could
 * implement an actual virtio goldfish pipe, but this hijacking of virgl  is
 * done in order to avoid any guest kernel changes. */

#include "virglrenderer.h"


#ifdef __cplusplus
extern "C" {
#endif
typedef uint32_t VirtioGpuCtxId;
struct virgl_renderer_virtio_interface*
    get_goldfish_pipe_virgl_renderer_virtio_interface(void);

/* Needed for goldfish pipe */
void virgl_write_fence(void *opaque, uint32_t fence);

#ifdef _WIN32
#define VG_EXPORT __declspec(dllexport)
#else
#define VG_EXPORT __attribute__((visibility("default")))
#endif

VG_EXPORT void virtio_goldfish_pipe_reset(void* hwpipe, void* hostpipe);

#define VIRTIO_GOLDFISH_EXPORT_API
#ifdef VIRTIO_GOLDFISH_EXPORT_API

VG_EXPORT int pipe_virgl_renderer_init(void *cookie,
                                       int flags,
                                       struct virgl_renderer_callbacks *cb);
VG_EXPORT void pipe_virgl_renderer_poll(void);
VG_EXPORT void* pipe_virgl_renderer_get_cursor_data(
    uint32_t resource_id, uint32_t *width, uint32_t *height);
VG_EXPORT int pipe_virgl_renderer_resource_create(
    struct virgl_renderer_resource_create_args *args,
    struct iovec *iov, uint32_t num_iovs);
VG_EXPORT void pipe_virgl_renderer_resource_unref(uint32_t res_handle);
VG_EXPORT int pipe_virgl_renderer_context_create(
    uint32_t handle, uint32_t nlen, const char *name);
VG_EXPORT void pipe_virgl_renderer_context_destroy(uint32_t handle);
VG_EXPORT int pipe_virgl_renderer_submit_cmd(void *buffer,
                                          int ctx_id,
                                          int bytes);
VG_EXPORT int pipe_virgl_renderer_transfer_read_iov(
    uint32_t handle, uint32_t ctx_id,
    uint32_t level, uint32_t stride,
    uint32_t layer_stride,
    struct virgl_box *box,
    uint64_t offset, struct iovec *iov,
    int iovec_cnt);
VG_EXPORT int pipe_virgl_renderer_transfer_write_iov(
    uint32_t handle,
    uint32_t ctx_id,
    int level,
    uint32_t stride,
    uint32_t layer_stride,
    struct virgl_box *box,
    uint64_t offset,
    struct iovec *iovec,
    unsigned int iovec_cnt);
VG_EXPORT void pipe_virgl_renderer_get_cap_set(uint32_t, uint32_t*, uint32_t*);
VG_EXPORT void pipe_virgl_renderer_fill_caps(uint32_t, uint32_t, void *caps);

VG_EXPORT int pipe_virgl_renderer_resource_attach_iov(
    int res_handle, struct iovec *iov,
    int num_iovs);
VG_EXPORT void pipe_virgl_renderer_resource_detach_iov(
    int res_handle, struct iovec **iov, int *num_iovs);
VG_EXPORT int pipe_virgl_renderer_create_fence(
    int client_fence_id, uint32_t cmd_type);
VG_EXPORT void pipe_virgl_renderer_force_ctx_0(void);
VG_EXPORT void pipe_virgl_renderer_ctx_attach_resource(
    int ctx_id, int res_handle);
VG_EXPORT void pipe_virgl_renderer_ctx_detach_resource(
    int ctx_id, int res_handle);
VG_EXPORT int pipe_virgl_renderer_resource_get_info(
    int res_handle,
    struct virgl_renderer_resource_info *info);

VG_EXPORT void stream_renderer_flush_resource_and_readback(
    uint32_t res_handle, uint32_t x, uint32_t y, uint32_t width, uint32_t height,
    void* pixels, uint32_t max_bytes);

VG_EXPORT void stream_renderer_resource_create_v2(
    uint32_t res_handle, uint64_t hvaId);

#define STREAM_MEM_HANDLE_TYPE_OPAQUE_FD 0x1
#define STREAM_MEM_HANDLE_TYPE_DMABUF 0x2
#define STREAM_MEM_HANDLE_TYPE_OPAQUE_WIN32 0x3
#define STREAM_MEM_HANDLE_TYPE_SHM 0x4
#define STREAM_FENCE_HANDLE_TYPE_OPAQUE_FD 0x10
#define STREAM_FENCE_HANDLE_TYPE_SYNC_FD 0x11
#define STREAM_FENCE_HANDLE_TYPE_OPAQUE_WIN32 0x12
struct stream_renderer_handle {
    int64_t os_handle;
    uint32_t handle_type;
};

struct stream_renderer_create_blob {
    uint32_t blob_mem;
    uint32_t blob_flags;
    uint64_t blob_id;
    uint64_t size;
};

#define STREAM_BLOB_MEM_GUEST 1
#define STREAM_BLOB_MEM_HOST3D 2
#define STREAM_BLOB_MEM_HOST3D_GUEST 3

#define STREAM_BLOB_FLAG_USE_MAPPABLE 1
#define STREAM_BLOB_FLAG_USE_SHAREABLE 2
#define STREAM_BLOB_FLAG_USE_CROSS_DEVICE 4
#define STREAM_BLOB_FLAG_CREATE_GUEST_HANDLE 8

VG_EXPORT int stream_renderer_create_blob(uint32_t ctx_id, uint32_t res_handle,
                                          const struct stream_renderer_create_blob *create_blob,
                                          const struct iovec *iovecs, uint32_t num_iovs,
                                          const struct stream_renderer_handle *handle);

VG_EXPORT int stream_renderer_export_blob(uint32_t res_handle,
                                          struct stream_renderer_handle *handle);

VG_EXPORT int stream_renderer_resource_map(uint32_t res_handle, void** hvaOut, uint64_t* sizeOut);
VG_EXPORT int stream_renderer_resource_unmap(uint32_t res_handle);

VG_EXPORT int stream_renderer_context_create(uint32_t ctx_id, uint32_t nlen, const char *name,
                                             uint32_t context_init);

VG_EXPORT int stream_renderer_context_create_fence(
    uint64_t fence_id, uint32_t ctx_id, uint8_t ring_idx);

// Platform resources and contexts support
#define STREAM_RENDERER_PLATFORM_RESOURCE_TYPE_EGL_NATIVE_PIXMAP 0x01
#define STREAM_RENDERER_PLATFORM_RESOURCE_TYPE_EGL_IMAGE 0x02

VG_EXPORT int stream_renderer_platform_import_resource(int res_handle, int res_type, void* resource);
VG_EXPORT int stream_renderer_platform_resource_info(int res_handle, int* width, int*  height, int* internal_format);
VG_EXPORT void* stream_renderer_platform_create_shared_egl_context(void);
VG_EXPORT int stream_renderer_platform_destroy_shared_egl_context(void*);

#define STREAM_RENDERER_MAP_CACHE_MASK      0x0f
#define STREAM_RENDERER_MAP_CACHE_NONE      0x00
#define STREAM_RENDERER_MAP_CACHE_CACHED    0x01
#define STREAM_RENDERER_MAP_CACHE_UNCACHED  0x02
#define STREAM_RENDERER_MAP_CACHE_WC        0x03
VG_EXPORT int stream_renderer_resource_map_info(uint32_t res_handle, uint32_t *map_info);

#else

#define VG_EXPORT

#endif // !VIRTIO_GOLDFISH_EXPORT_API

#ifdef __cplusplus
} // extern "C"
#endif

// based on VIRGL_RENDERER_USE* and friends
enum RendererFlags {
    GFXSTREAM_RENDERER_FLAGS_USE_EGL_BIT = 1 << 0,
    GFXSTREAM_RENDERER_FLAGS_THREAD_SYNC = 1 << 1,
    GFXSTREAM_RENDERER_FLAGS_USE_GLX_BIT = 1 << 2,
    GFXSTREAM_RENDERER_FLAGS_USE_SURFACELESS_BIT = 1 << 3,
    GFXSTREAM_RENDERER_FLAGS_USE_GLES_BIT = 1 << 4,
    GFXSTREAM_RENDERER_FLAGS_NO_VK_BIT = 1 << 5,  // for disabling vk
    GFXSTREAM_RENDERER_FLAGS_IGNORE_HOST_GL_ERRORS_BIT =
        1 << 6,  // control IgnoreHostOpenGLErrors flag
    GFXSTREAM_RENDERER_FLAGS_NATIVE_TEXTURE_DECOMPRESSION_BIT =
        1 << 7,  // Attempt GPU texture decompression
    GFXSTREAM_RENDERER_FLAGS_ENABLE_BPTC_TEXTURES_BIT =
        1 << 8,  // enable BPTC texture support if available
    GFXSTREAM_RENDERER_FLAGS_ENABLE_GLES31_BIT =
        1 << 9,  // disables the PlayStoreImage flag
    GFXSTREAM_RENDERER_FLAGS_ENABLE_S3TC_TEXTURES_BIT =
        1 << 10,  // enable S3TC texture support if available
    GFXSTREAM_RENDERER_FLAGS_NO_SYNCFD_BIT = 1 << 20,  // for disabling syncfd
    GFXSTREAM_RENDERER_FLAGS_GUEST_USES_ANGLE = 1 << 21,
    GFXSTREAM_RENDERER_FLAGS_VULKAN_NATIVE_SWAPCHAIN_BIT = 1 << 22,
    GFXSTREAM_RENDERER_FLAGS_ASYNC_FENCE_CB = 1 << 23,
};

#endif
