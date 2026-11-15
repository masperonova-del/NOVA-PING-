#include <stdint.h>
#include <stdatomic.h>

/*
 * The telemetry producer must not fall back to a hidden locking runtime on
 * any supported Android ABI. This is a compile-time target property check.
 */
_Static_assert(__atomic_always_lock_free(sizeof(_Atomic uint32_t), 0),
               "32-bit queue atomics must be always lock-free on this ABI");

int main(void)
{
    _Atomic uint32_t value = 0u;
    atomic_store_explicit(&value, 1u, memory_order_relaxed);
    return atomic_load_explicit(&value, memory_order_relaxed) == 1u ? 0 : 1;
}
