package com.example.invoicer

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import java.util.TreeMap
import java.util.UUID
import java.util.zip.Deflater

data class Line(val name: String, val qty: Double, val price: Double)
data class Invoice(val id: String, val no: String, val date: String, val customer: String, val phone: String,
                   val note: String, val items: List<Line>) {
    val total get() = items.sumOf { it.qty * it.price }
}

fun num(d: Double) = if (d == Math.rint(d)) d.toLong().toString() else d.toString()
fun money(n: Double, cur: String = "") = cur + String.format("%,.2f", n)
fun monthLabel(k: String) = runCatching {
    YearMonth.parse(k).let { it.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + it.year }
}.getOrDefault(k)

class Vm(private val sp: SharedPreferences) {
    val invoices = mutableStateListOf<Invoice>()
    var biz by mutableStateOf(sp.getString("biz", "") ?: "")
    var url by mutableStateOf(sp.getString("url", "") ?: "")
    var cur by mutableStateOf(sp.getString("cur", "") ?: "")

    init {
        runCatching {
            val a = JSONArray(sp.getString("inv", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val li = o.getJSONArray("items")
                invoices.add(Invoice(o.getString("id"), o.getString("no"), o.getString("date"), o.getString("customer"),
                    o.getString("phone"), o.getString("note"), (0 until li.length()).map {
                        val l = li.getJSONObject(it); Line(l.getString("n"), l.getDouble("q"), l.getDouble("p")) }))
            }
        }
    }

    private fun persist() {
        val a = JSONArray()
        invoices.forEach { v ->
            a.put(JSONObject().put("id", v.id).put("no", v.no).put("date", v.date).put("customer", v.customer)
                .put("phone", v.phone).put("note", v.note).put("items", JSONArray().also { li ->
                    v.items.forEach { li.put(JSONObject().put("n", it.name).put("q", it.qty).put("p", it.price)) } }))
        }
        sp.edit().putString("inv", a.toString()).apply()
    }

    fun saveSettings() { sp.edit().putString("biz", biz).putString("url", url).putString("cur", cur).apply() }

    fun add(customer: String, phone: String, note: String, items: List<Line>): Invoice {
        val n = sp.getInt("next", 1); sp.edit().putInt("next", n + 1).apply()
        val inv = Invoice(UUID.randomUUID().toString(), "INV-%04d".format(n), LocalDate.now().toString(), customer, phone, note, items)
        invoices.add(0, inv); persist(); return inv
    }

    fun remove(id: String) { invoices.removeAll { it.id == id }; persist() }

    fun recentItems() = invoices.flatMap { v -> v.items.map { it.name.trim() } }.distinctBy { it.lowercase() }.take(8)

    // The invoice data travels inside the link itself (compressed), so no server is needed.
    fun link(inv: Invoice): String {
        val j = JSONArray().put(biz).put(inv.no).put(inv.date).put(inv.customer)
            .put(JSONArray().also { a -> inv.items.forEach { a.put(JSONArray().put(it.name).put(it.qty).put(it.price)) } })
            .put(inv.note).put(cur)
        val raw = j.toString().toByteArray()
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(raw); d.finish()
        val buf = ByteArray(raw.size + 64); val n = d.deflate(buf); d.end()
        return url.trim().trimEnd('/') + "/#" + Base64.encodeToString(buf.copyOf(n), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun smsText(inv: Invoice) = "Hi ${inv.customer}, your invoice ${inv.no}" + (if (biz.isBlank()) "" else " from $biz") +
        " for ${money(inv.total, cur)} is ready. View or download it here: ${link(inv)}"

    fun ready(c: Context): Boolean {
        if (url.isBlank()) Toast.makeText(c, "Set your invoice page URL in Settings first", Toast.LENGTH_LONG).show()
        return url.isNotBlank()
    }
}

fun sendSms(c: Context, phone: String, body: String) {
    runCatching { c.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone))).putExtra("sms_body", body)) }
        .onFailure { Toast.makeText(c, "No SMS app found", Toast.LENGTH_SHORT).show() }
}

fun shareText(c: Context, t: String) =
    c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Share"))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = Vm(getSharedPreferences("invoicer", MODE_PRIVATE))
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { App(vm) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: Vm) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<Invoice?>(null) }
    if (creating) {
        BackHandler { creating = false }
        NewInvoice(vm, { creating = false }) { creating = false; view = it }
    } else {
        Scaffold(
            topBar = { TopAppBar(title = { Text(if (vm.biz.isBlank()) "Invoicer" else vm.biz) }) },
            bottomBar = {
                NavigationBar {
                    listOf("🧾" to "Invoices", "📊" to "Stats", "⚙️" to "Settings").forEachIndexed { i, (e, t) ->
                        NavigationBarItem(tab == i, { tab = i }, { Text(e) }, label = { Text(t) })
                    }
                }
            },
            floatingActionButton = {
                if (tab == 0) ExtendedFloatingActionButton({ creating = true }) { Text("+ New invoice") }
            }
        ) { pad ->
            Box(Modifier.padding(pad).padding(horizontal = 16.dp)) {
                when (tab) { 0 -> InvoiceList(vm) { view = it }; 1 -> Stats(vm); else -> SettingsScreen(vm) }
            }
        }
    }
    view?.let { InvoiceDialog(vm, it) { view = null } }
}

@Composable
fun InvoiceList(vm: Vm, onOpen: (Invoice) -> Unit) {
    if (vm.invoices.isEmpty()) { Text("No invoices yet. Tap “+ New invoice”.", Modifier.padding(24.dp)); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 90.dp)) {
        items(vm.invoices, key = { it.id }) { v ->
            ElevatedCard(Modifier.fillMaxWidth().clickable { onOpen(v) }) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(v.customer, fontWeight = FontWeight.SemiBold)
                        Text("${v.no} · ${v.date}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(money(v.total, vm.cur), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

data class Draft(val name: String = "", val qty: String = "1", val price: String = "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewInvoice(vm: Vm, onCancel: () -> Unit, onSaved: (Invoice) -> Unit) {
    var cust by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf(Draft()) }
    val valid = lines.filter { it.name.isNotBlank() && (it.qty.toDoubleOrNull() ?: 0.0) > 0 && it.price.toDoubleOrNull() != null }
    val total = valid.sumOf { it.qty.toDouble() * it.price.toDouble() }
    val dec = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    Scaffold(
        topBar = { TopAppBar(title = { Text("New invoice") }, navigationIcon = { TextButton(onCancel) { Text("Cancel") } }) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Total ${money(total, vm.cur)}", fontWeight = FontWeight.Bold)
                    Button(enabled = cust.isNotBlank() && valid.isNotEmpty(), onClick = {
                        onSaved(vm.add(cust.trim(), phone.trim(), note.trim(), valid.map { Line(it.name.trim(), it.qty.toDouble(), it.price.toDouble()) }))
                    }) { Text("Save invoice") }
                }
            }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OutlinedTextField(cust, { cust = it }, Modifier.fillMaxWidth(), label = { Text("Customer name") }, singleLine = true) }
            item {
                OutlinedTextField(phone, { phone = it }, Modifier.fillMaxWidth(), label = { Text("Customer phone (for SMS, with country code)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            }
            item {
                Text("Items", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.recentItems().forEach { n ->
                        AssistChip({
                            val l = lines.lastIndex
                            if (lines[l].name.isBlank()) lines[l] = lines[l].copy(name = n) else lines.add(Draft(name = n))
                        }, { Text(n) })
                    }
                }
            }
            itemsIndexed(lines) { i, d ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(d.name, { lines[i] = lines[i].copy(name = it) }, Modifier.weight(2f), label = { Text("Item") }, singleLine = true)
                    OutlinedTextField(d.qty, { lines[i] = lines[i].copy(qty = it) }, Modifier.weight(1f), label = { Text("Qty") }, singleLine = true, keyboardOptions = dec)
                    OutlinedTextField(d.price, { lines[i] = lines[i].copy(price = it) }, Modifier.weight(1f), label = { Text("Price") }, singleLine = true, keyboardOptions = dec)
                }
            }
            item { OutlinedButton({ lines.add(Draft()) }) { Text("+ Add item") } }
            item { OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note (optional)") }) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun InvoiceDialog(vm: Vm, inv: Invoice, onClose: () -> Unit) {
    val c = LocalContext.current
    var armed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("${inv.no} · ${inv.customer}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                inv.items.forEach {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${num(it.qty)} × ${it.name}", Modifier.weight(1f)); Text(money(it.qty * it.price, vm.cur))
                    }
                }
                HorizontalDivider()
                Text("Total ${money(inv.total, vm.cur)}", fontWeight = FontWeight.Bold)
                if (inv.phone.isNotBlank()) Button({ if (vm.ready(c)) sendSms(c, inv.phone, vm.smsText(inv)) }, Modifier.fillMaxWidth()) { Text("Send SMS to ${inv.phone}") }
                OutlinedButton({ if (vm.ready(c)) shareText(c, vm.smsText(inv)) }, Modifier.fillMaxWidth()) { Text("Share link") }
                TextButton({ if (armed) { vm.remove(inv.id); onClose() } else armed = true }) { Text(if (armed) "Tap again to delete" else "Delete invoice") }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Close") } }
    )
}

class Agg(val name: String) { var qty = 0.0; val months = TreeMap<String, Double>() }

@Composable
fun Stats(vm: Vm) {
    val items = LinkedHashMap<String, Agg>()
    val monthTotals = TreeMap<String, Double>()
    val customers = LinkedHashMap<String, Pair<String, Double>>()
    vm.invoices.forEach { inv ->
        val mo = inv.date.take(7); val ck = inv.customer.trim().lowercase()
        inv.items.forEach { l ->
            val a = items.getOrPut(l.name.trim().lowercase()) { Agg(l.name.trim()) }
            a.qty += l.qty; a.months[mo] = (a.months[mo] ?: 0.0) + l.qty
            monthTotals[mo] = (monthTotals[mo] ?: 0.0) + l.qty
            customers[ck] = inv.customer.trim() to ((customers[ck]?.second ?: 0.0) + l.qty)
        }
    }
    if (items.isEmpty()) { Text("Create invoices to see item statistics.", Modifier.padding(24.dp)); return }
    val ranked = items.values.sortedByDescending { it.qty }
    val max = ranked.first().qty
    val best = monthTotals.maxByOrNull { it.value }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Overview", fontWeight = FontWeight.SemiBold)
                    Text("${vm.invoices.size} invoices · ${num(ranked.sumOf { it.qty })} units sold · ${ranked.size} different items")
                    best?.let { Text("Best month: ${monthLabel(it.key)} (${num(it.value)} units)") }
                    Text("Top item: ${ranked.first().name} · Slowest: ${ranked.last().name}")
                }
            }
        }
        item { Text("Most sold items (tap for monthly detail)", style = MaterialTheme.typography.titleSmall) }
        items(ranked, key = { it.name.lowercase() }) { a ->
            ElevatedCard(Modifier.fillMaxWidth().clickable { open = if (open == a.name) null else a.name }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(a.name, fontWeight = FontWeight.SemiBold); Text("${num(a.qty)} units")
                    }
                    LinearProgressIndicator(progress = { (a.qty / max).toFloat() }, Modifier.fillMaxWidth())
                    if (open == a.name) {
                        a.months.maxByOrNull { it.value }?.let { Text("Highest month: ${monthLabel(it.key)} (${num(it.value)} units)", fontWeight = FontWeight.Medium) }
                        a.months.entries.reversed().forEach {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(monthLabel(it.key), style = MaterialTheme.typography.bodySmall); Text(num(it.value), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        item { Text("Top customers (by units bought)", style = MaterialTheme.typography.titleSmall) }
        items(customers.values.sortedByDescending { it.second }.take(5), key = { it.first }) { (n, q) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(n); Text("${num(q)} units") }
        }
    }
}

@Composable
fun SettingsScreen(vm: Vm) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(vm.biz, { vm.biz = it; vm.saveSettings() }, Modifier.fillMaxWidth(), label = { Text("Business name") }, singleLine = true)
        OutlinedTextField(vm.cur, { vm.cur = it; vm.saveSettings() }, Modifier.fillMaxWidth(), label = { Text("Currency symbol (optional)") }, singleLine = true)
        OutlinedTextField(vm.url, { vm.url = it; vm.saveSettings() }, Modifier.fillMaxWidth(), label = { Text("Invoice page URL") }, singleLine = true,
            supportingText = { Text("Your GitHub Pages address, e.g. https://yourname.github.io/InvoiceApp") })
    }
}
