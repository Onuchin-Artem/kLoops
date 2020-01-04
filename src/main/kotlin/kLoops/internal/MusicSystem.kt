package kLoops.internal

import kLoops.music.LoopContext
import kLoops.music.NoteLength
import kLoops.music.beat
import java.util.concurrent.ArrayBlockingQueue

enum class CommandType {
    Message, Event, Nothing
}

data class Command(val beginOfCommand: NoteLength, val type: CommandType, val command: String) {
}

class MusicPhraseRunner(val context: LoopContext, val block: LoopContext.() -> Unit) {
    val commands = mutableListOf<Command>()
    var beginTime = nextBarBit().beat()

    fun addCommand(noteLength: NoteLength, commandTemplate: String) {

        val beginBeats = beginTime.beatInBar().toBeats() + 1.0
        val command = commandTemplate.replace("{time}", beginBeats.toString())
        commands.add(Command(beginTime, CommandType.Message, command))
        addWait(noteLength)
    }

    fun addWait(noteLength: NoteLength) {
        beginTime += noteLength
        commands.add(Command(beginTime, CommandType.Nothing, ""))
    }

    fun addEvent(triggerEvent: String) {
        commands.add(Command(beginTime, CommandType.Event, triggerEvent))
    }

    fun processBitUpdate(bit: Int): Int {

        if (commands.isEmpty()) {
            return 0
        }
        var processedNum = 0
        var topCommand: Command? = commands[0]

        while (topCommand != null &&
                topCommand.beginOfCommand >= bit.beat()
                && topCommand.beginOfCommand < (bit + 1).beat()) {
            commands.removeAt(0)
            processedNum++
            when (topCommand.type) {
                CommandType.Message -> commandQueue.offer(topCommand.command)
                CommandType.Event -> MusicPhraseRunners.processEvent(topCommand.command, topCommand.beginOfCommand)
                CommandType.Nothing -> {
                }
            }
            if (commands.isNotEmpty()) topCommand = commands[0]
            else topCommand = null
        }
        return processedNum
    }

    fun runCommands() {
        block.invoke(context)
    }

    override operator fun equals(other: Any?): Boolean =
            other is MusicPhraseRunner && context.loopName == other.context.loopName

    override fun hashCode(): Int = context.loopName.hashCode()
}

val eventsQueue = ArrayBlockingQueue<String>(1024)

object MusicPhraseRunners {
    private val runnersMap = mutableMapOf<String, MusicPhraseRunner>()
    private val eventsListeners = mutableMapOf<String, MutableSet<MusicPhraseRunner>>()

    //called from music loop only
    @Synchronized
    fun processBitUpdate(bit: Int) {

        if (bit % 4 == 0) {
            while (eventsQueue.isNotEmpty()) {
                processEvent(eventsQueue.poll(), bit.beat())
            }
        }
        do {
            val numberOfProcessedCommands = runnersMap.values.map { runner -> runner.processBitUpdate(bit) }.sum()
        } while (numberOfProcessedCommands > 0)
    }

    //called from music loop only
    @Synchronized
    fun getMusicPhrase(context: LoopContext): MusicPhraseRunner = runnersMap[context.loopName]!!

    @Synchronized
    fun registerEventListener(context: LoopContext, block: LoopContext.() -> Unit) {
        val newRunner = MusicPhraseRunner(context, block)
        runnersMap[context.loopName] = newRunner
        eventsListeners.keys.forEach { event -> eventsListeners[event]!!.remove(newRunner) }
        context.events.forEach { event ->
            eventsListeners.computeIfAbsent(event) { mutableSetOf() }
            eventsListeners[event]!!.add(newRunner)
        }
    }


    //called from music loop only
    @Synchronized
    fun processEvent(eventName: String, newBeginTime: NoteLength) {
        eventsListeners[eventName]?.forEach {
            it.beginTime = newBeginTime
            it.commands.clear()
            it.runCommands()
        }
    }
}

