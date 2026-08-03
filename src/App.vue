<script>
import { hideNativeTitleNView } from '@/utils/app-plus-style'
import { ensureAndroidNamaAlive } from '@/utils/nama-app'

export default {
  onLaunch() {
    // #ifdef APP-PLUS
    hideNativeTitleNView()
    // #endif
  },
  onShow() {
    // #ifdef APP-PLUS
    hideNativeTitleNView()
    ensureAndroidNamaAlive().catch(() => undefined)
    // #endif
  },
}
</script>

<style></style>
