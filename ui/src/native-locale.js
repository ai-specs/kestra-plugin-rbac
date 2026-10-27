import en from 'element-plus/es/locale/lang/en'
import de from 'element-plus/es/locale/lang/de'
import es from 'element-plus/es/locale/lang/es'
import fr from 'element-plus/es/locale/lang/fr'
import hi from 'element-plus/es/locale/lang/hi'
import it from 'element-plus/es/locale/lang/it'
import ja from 'element-plus/es/locale/lang/ja'
import ko from 'element-plus/es/locale/lang/ko'
import pl from 'element-plus/es/locale/lang/pl'
import pt from 'element-plus/es/locale/lang/pt'
import ptBR from 'element-plus/es/locale/lang/pt-br'
import ru from 'element-plus/es/locale/lang/ru'
import zhCN from 'element-plus/es/locale/lang/zh-cn'
import {resolveLocale} from '../../.integration/ui-sdk/locales.mjs'

const locales = Object.freeze({en, de, es, fr, hi, it, ja, ko, pl, pt, pt_BR: ptBR, ru, zh_CN: zhCN})
export const nativeLocale = value => locales[resolveLocale(value)] ?? en
