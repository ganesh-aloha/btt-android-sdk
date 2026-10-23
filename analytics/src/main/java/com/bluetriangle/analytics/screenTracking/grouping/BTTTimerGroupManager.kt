package com.bluetriangle.analytics.screenTracking.grouping

import com.bluetriangle.analytics.Timer
import com.bluetriangle.analytics.Tracker
import com.bluetriangle.analytics.model.Screen

internal class BTTTimerGroupManager(var groupIdleTime: Int) {

    private val activeGroups = mutableListOf<BTTTimerGroup>()
    private val shouldRegisterTap = true

    fun add(screen: Screen, timer: Timer) {
        val lastActive = getLastActiveGroup()
        val lastUserAction = Tracker.instance?.lastTouchEventTimestamp?:0L

        if(lastActive == null || (shouldRegisterTap && lastUserAction > lastActive.startTime)) {
            val groupingCause = if(lastActive == null) {
                GroupingCause.Timeout(System.currentTimeMillis() - lastGroupStartTime)
            } else {
                GroupingCause.Tap(lastUserAction - lastGroupStartTime)
            }
            createNewGroupAndAdd(screen, timer, groupingCause = groupingCause)
        } else {
            lastActive.add(screen, timer)
        }
    }

    private fun getLastActiveGroup(): BTTTimerGroup? {
        return activeGroups.lastOrNull { !it.isClosed }
    }

    private fun createNewGroup(groupingCause: GroupingCause): BTTTimerGroup {
        submitAllExistingTimers()

        val newGroup = BTTTimerGroup(groupIdleTime = groupIdleTime, onCompleted = this::onGroupCompleted, groupingCause = groupingCause)
        addToActiveGroup(newGroup)
        return newGroup
    }

    private fun createNewGroupAndAdd(
        screen: Screen,
        timer: Timer,
        groupingCause: GroupingCause
    ) {
        createNewGroup(groupingCause).apply {
            add(screen, timer)
        }
    }


    private fun addToActiveGroup(group: BTTTimerGroup) {
        activeGroups.add(group)
    }

    private fun submitAllExistingTimers() {
        activeGroups.forEach {
            it.submit()
            it.flush()
        }
        activeGroups.clear()
    }

    /**
     * The submitted group timer of the group the given screen timer belongs to
     */
    fun groupTimerOf(timer: Timer): Timer? {
        return activeGroups.lastOrNull { it.contains(timer) }?.timer
    }

    fun setGroupName(groupName: String) {
        activeGroups.lastOrNull { !it.isSubmitted }?.setGroupName(groupName)
    }

    fun onGroupCompleted(group: BTTTimerGroup) {
        group.submit()
        group.flush()
        activeGroups.remove(group)
    }

    fun setNewGroup(groupName: String) {
        val timeInterval = System.currentTimeMillis() - lastGroupStartTime
        createNewGroup(groupingCause = GroupingCause.Manual(timeInterval)).setManualGroupName(groupName)
    }

    val lastGroupStartTime: Long
        get() = activeGroups.lastOrNull()?.startTime?:System.currentTimeMillis()

    fun destroy() {
        activeGroups.forEach {
            it.flush()
            it.end()
        }
        activeGroups.clear()
    }
}